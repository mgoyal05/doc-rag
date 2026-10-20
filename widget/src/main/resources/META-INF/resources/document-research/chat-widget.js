const finished = new Set(['COMPLETED', 'NEEDS_CLARIFICATION', 'FAILED', 'CANCELLED', 'INTERRUPTED']);
const styles = `
:host{all:initial;--research-accent:#173f64;--research-surface:#fff;--research-text:#172331;font:14px system-ui,sans-serif;color:var(--research-text);position:fixed;right:22px;bottom:22px;z-index:1000}
*{box-sizing:border-box}button,input,textarea,select{font:inherit}button{cursor:pointer}button:disabled{cursor:wait;opacity:.55}
button:focus-visible,textarea:focus-visible,select:focus-visible,summary:focus-visible{outline:3px solid #458bd0;outline-offset:2px}
.launcher{width:58px;height:58px;border:0;border-radius:50%;color:white;background:var(--research-accent);box-shadow:0 4px 16px #0004;font-size:26px;float:right}
.panel{width:400px;height:min(650px,calc(100dvh - 105px));max-width:calc(100vw - 32px);display:flex;flex-direction:column;background:var(--research-surface);border:1px solid #c8d4df;border-radius:14px;box-shadow:0 8px 36px #0003;margin-bottom:12px;overflow:hidden}
[hidden]{display:none!important}.head{display:flex;align-items:center;gap:8px;padding:14px;background:var(--research-accent);color:white}.head strong{flex:1}.head button{border:1px solid #ffffff66;background:transparent;color:white;border-radius:5px;padding:5px 8px}
.notice{background:#f0f5fa;padding:9px 12px;font-size:12px;line-height:1.4}.history{display:flex;gap:8px;padding:8px;border-bottom:1px solid #e0e6ec}.history select{min-width:0;flex:1}.history button{border:1px solid #b8c6d2;background:white;border-radius:4px}
.messages{flex:1;overflow:auto;padding:12px;min-height:120px;overscroll-behavior:contain}.message{padding:10px 12px;border-radius:10px;margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.5;background:#eef4fa}.user{margin-left:25px;background:#dcebf8}.assistant{margin-right:12px;background:#f3f5f7}.meta{font-size:11px;color:#53697d;margin:4px 0}details{margin:8px 0;border-top:1px solid #d6dfe8;padding-top:6px}summary{cursor:pointer}pre{font:12px system-ui;white-space:pre-wrap;overflow-wrap:anywhere}a{color:#174e86}.scope{font-size:12px;color:#536475}
.status{padding:6px 12px;font-size:12px;color:#405b72;min-height:28px}.composer{border-top:1px solid #dbe3ec;padding:10px;display:flex;gap:7px;align-items:flex-end}.composer textarea{min-width:0;resize:none;flex:1;border:1px solid #b9c9d7;border-radius:7px;padding:8px;max-height:100px}.composer button{border:0;border-radius:6px;background:var(--research-accent);color:white;padding:9px}.composer .attach{background:#eef3f8;color:#173f64}.cancel{margin:0 12px 8px;border:1px solid #becbd6;border-radius:5px;background:white;padding:5px}
@media(max-width:480px){:host{right:12px;bottom:12px}.panel{width:calc(100vw - 24px);height:calc(100dvh - 96px);max-width:none}.launcher{width:54px;height:54px}}
`;

export class ResearchChatWidget extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({mode: 'open'});
        this.options = {};
        this.conversationId = null;
        this.jobId = null;
        this.events = null;
        this.loading = false;
        this.shadowRoot.innerHTML = `<style>${styles}</style><section class="panel" role="dialog" aria-label="Document research chat" hidden>
      <div class="head"><strong>Research assistant</strong><button class="new" aria-label="Start a new conversation">New</button><button class="close" aria-label="Close research chat">×</button></div>
      <div class="notice">Uploaded reports are shared with authenticated application users. Your conversations are private.</div>
      <div class="history"><select aria-label="Conversation history"></select><button class="refresh" aria-label="Reload conversation and reconnect progress">↻</button></div>
      <div class="messages" role="log" aria-label="Conversation messages" aria-live="polite"></div><div class="status" role="status"></div>
      <button class="cancel" hidden>Cancel processing</button><form class="composer"><input type="file" accept="application/pdf" hidden>
      <button type="button" class="attach" aria-label="Attach a PDF report">＋</button><textarea rows="2" maxlength="4000" placeholder="Ask about your uploaded reports…" aria-label="Research question"></textarea><button type="submit" class="send">Send</button></form>
      </section><button class="launcher" aria-label="Open research chat" aria-expanded="false">💬</button>`;
        this.el = selector => this.shadowRoot.querySelector(selector);
        this.el('.launcher').onclick = () => this.el('.panel').hidden ? this.open() : this.close();
        this.el('.close').onclick = () => this.close();
        this.el('.new').onclick = () => this.guard(() => this.newConversation());
        this.el('.refresh').onclick = () => this.guard(() => this.reload());
        this.el('select').onchange = () => this.guard(() => this.selectConversation(this.el('select').value));
        this.el('.attach').onclick = () => this.el('input').click();
        this.el('input').onchange = () => this.guard(() => this.upload());
        this.el('.composer').onsubmit = event => {
            event.preventDefault();
            this.guard(() => this.send());
        };
        this.el('textarea').onkeydown = event => {
            if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                this.guard(() => this.send());
            }
        };
        this.el('.cancel').onclick = () => this.guard(async () => {
            if (this.jobId) await this.request(`/jobs/${this.jobId}/cancel`, {method: 'POST'});
            await this.reload();
        });
        this.addEventListener('keydown', event => {
            if (event.key === 'Escape') {
                event.preventDefault();
                this.close();
            }
        });
    }

    configure(options = {}) {
        this.options = {apiBase: '/api/research', title: 'Research assistant', ...this.options, ...options};
        const url = new URL(this.options.apiBase, location.href);
        if (url.origin !== location.origin) throw new Error('The default widget requires a same-origin API');
        this.base = url.pathname.replace(/\/$/, '');
        this.el('.head strong').textContent = this.options.title;
        return this;
    }

    setContext(context) {
        this.context = context ? structuredClone(context) : null;
        return this;
    }

    async open() {
        this.el('.panel').hidden = false;
        this.el('.launcher').setAttribute('aria-expanded', 'true');
        this.el('textarea').focus();
        await this.guard(() => this.initialize());
    }

    close() {
        this.el('.panel').hidden = true;
        this.el('.launcher').setAttribute('aria-expanded', 'false');
        this.el('.launcher').focus();
    }

    destroy() {
        if (this.events) this.events.close();
        this.remove();
    }

    disconnectedCallback() {
        if (this.events) this.events.close();
    }

    async guard(action) {
        try {
            await action();
        } catch (error) {
            this.status(error.message);
        }
    }

    status(text) {
        this.el('.status').textContent = text;
    }

    async request(path, options = {}) {
        const headers = new Headers(options.headers);
        if (options.method && options.method !== 'GET' && this.config) headers.set(this.config.csrfHeader, this.config.csrfToken);
        const response = await fetch(this.base + path, {
            ...options,
            headers,
            credentials: 'same-origin',
            cache: 'no-store'
        });
        if (response.redirected) throw new Error('Sign in again to use research chat');
        if (!response.ok) {
            let message = `Request failed (${response.status})`;
            try {
                message = (await response.json()).error || message;
            } catch {
            }
            if (response.status === 401 || response.redirected) message = 'Sign in again to use research chat';
            throw new Error(message);
        }
        return response.json();
    }

    async initialize() {
        if (this.config) return;
        this.config = await this.request('/chat/config');
        try {
            await this.loadList();
            let saved;
            try {
                saved = localStorage.getItem(this.config.storageKey);
            } catch {
            }
            if (saved && this.conversations.some(c => c.id === saved)) await this.selectConversation(saved);
            else if (this.conversations.length) await this.selectConversation(this.conversations[0].id);
            else await this.newConversation();
        } catch (error) {
            this.config = null;
            throw error;
        }
    }

    async loadList() {
        this.conversations = await this.request('/conversations');
        const select = this.el('select');
        select.replaceChildren();
        for (const conversation of this.conversations) {
            const option = document.createElement('option');
            option.value = conversation.id;
            option.textContent = conversation.title;
            select.append(option);
        }
        if (this.conversationId) select.value = this.conversationId;
    }

    async newConversation() {
        if (this.jobId) return this.status('Finish or cancel the current turn before starting a new conversation.');
        const conversation = await this.request('/conversations', {method: 'POST'});
        await this.loadList();
        await this.selectConversation(conversation.id);
    }

    async selectConversation(id) {
        if (this.events) this.events.close();
        this.events = null;
        this.jobId = null;
        this.conversationId = id;
        try {
            localStorage.setItem(this.config.storageKey, id);
        } catch {
        }
        this.el('select').value = id;
        await this.reload();
    }

    busy(value) {
        this.el('.send').disabled = value;
        this.el('.attach').disabled = value;
        this.el('textarea').disabled = value;
        this.el('.new').disabled = value;
        this.el('.cancel').hidden = !this.jobId;
    }

    async reload() {
        if (!this.conversationId) return;
        const selected = this.conversationId;
        const response = await this.request(`/conversations/${selected}`);
        if (selected !== this.conversationId) return;
        this.render(response.messages);
        if (response.activeJob) {
            this.jobId = response.activeJob;
            this.busy(true);
            this.watch(response.activeJob);
        } else {
            if (this.events) this.events.close();
            this.events = null;
            this.jobId = null;
            this.busy(Boolean(response.conversation.activeTurn));
            this.status(response.conversation.activeTurn ? 'An earlier turn is active; reopen the conversation to reconnect.' : 'Ready. Ask a question or attach a PDF.');
        }
    }

    async send() {
        if (this.loading || this.jobId) return;
        const text = this.el('textarea').value.trim();
        if (!text) return;
        this.loading = true;
        this.busy(true);
        const selected = this.conversationId;
        try {
            const job = await this.request(`/conversations/${selected}/messages`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({text, context: this.context})
            });
            if (selected !== this.conversationId) return;
            this.el('textarea').value = '';
            this.jobId = job.id;
            await this.reload();
        } finally {
            this.loading = false;
            if (!this.jobId) this.busy(false);
        }
    }

    async upload() {
        const file = this.el('input').files[0];
        this.el('input').value = '';
        if (!file || this.jobId || this.loading) return;
        if (file.size > this.config.maxFileBytes) throw new Error('PDF exceeds the configured upload limit');
        const form = new FormData();
        form.append('file', file);
        this.loading = true;
        this.busy(true);
        this.status('Uploading PDF…');
        const selected = this.conversationId;
        try {
            const job = await this.request(`/conversations/${selected}/documents`, {method: 'POST', body: form});
            if (selected !== this.conversationId) return;
            this.jobId = job.id;
            await this.reload();
        } finally {
            this.loading = false;
            if (!this.jobId) this.busy(false);
        }
    }

    watch(id) {
        if (this.events) this.events.close();
        this.jobId = id;
        this.busy(true);
        this.status('Queued for processing…');
        const selected = this.conversationId;
        const events = new EventSource(this.base + `/jobs/${id}/events`);
        this.events = events;
        events.addEventListener('job', event => this.guard(async () => {
            if (events !== this.events || selected !== this.conversationId) return;
            const job = JSON.parse(event.data);
            this.status(job.state.replaceAll('_', ' ') + (job.error ? `: ${job.error}` : ''));
            if (finished.has(job.state)) {
                events.close();
                this.events = null;
                this.jobId = null;
                await this.loadList();
                if (selected !== this.conversationId) return;
                await this.reload();
                if (job.error && selected === this.conversationId) this.status(job.error);
            }
        }));
        events.onerror = () => {
            events.close();
            if (events !== this.events || selected !== this.conversationId) return;
            this.events = null;
            this.status('Progress connection interrupted. Use ↻ to reconnect.');
        };
    }

    render(messages) {
        const log = this.el('.messages');
        log.replaceChildren();
        if (!messages.length) {
            const welcome = document.createElement('p');
            welcome.textContent = 'Attach a report, then ask a question. I will cite the source pages and ask when the company or reporting period is unclear.';
            log.append(welcome);
        }
        for (const message of messages) {
            const user = document.createElement('div');
            user.className = 'message user';
            user.textContent = message.text;
            log.append(user);
            const result = message.result;
            if (result) {
                const reply = document.createElement('div');
                reply.className = 'message assistant';
                const intro = document.createElement('div');
                intro.textContent = result.message;
                reply.append(intro);
                if (result.answer) {
                    const validation = document.createElement('p');
                    validation.textContent = result.answer.validation?.accepted
                        ? 'Evidence support checked — inspect cited sources.'
                        : 'Historical answer — not verified by the current validation policy.';
                    reply.append(validation);
                    for (const claim of result.answer.claims) {
                        const line = document.createElement('p');
                        line.textContent = `${claim.text} [${claim.citations.join(', ')}]`;
                        reply.append(line);
                    }
                    for (const item of result.answer.evidence) {
                        const details = document.createElement('details');
                        const summary = document.createElement('summary');
                        summary.textContent = `${item.id} — ${item.company || 'Tool evidence'}${item.pages?.length ? ', pages ' + item.pages.join(', ') : ''}`;
                        const excerpt = document.createElement('pre');
                        excerpt.textContent = item.text;
                        const source = document.createElement('p');
                        source.textContent = item.source;
                        try {
                            const url = new URL(item.source, location.href);
                            if ((item.source.startsWith('/') || /^https?:\/\//.test(item.source)) && ['http:', 'https:'].includes(url.protocol)) {
                                const link = document.createElement('a');
                                link.href = url.href + (item.page ? '#page=' + item.page : '');
                                link.textContent = ' Open source';
                                link.target = '_blank';
                                link.rel = 'noopener noreferrer';
                                source.append(link);
                            }
                        } catch {
                        }
                        details.append(summary, source, excerpt);
                        reply.append(details);
                    }
                }
                log.append(reply);
            } else if (['FAILED', 'INTERRUPTED'].includes(message.state)) {
                const note = document.createElement('p');
                note.textContent = message.result?.failureCode
                    ? `Answer withheld: ${message.result.failureCode}.`
                    : 'Processing was interrupted or failed.';
                log.append(note);
            }
            if (['FAILED', 'INTERRUPTED'].includes(message.state)) {
                const retry = document.createElement('button');
                retry.textContent = 'Retry this turn';
                retry.onclick = () => this.guard(async () => {
                    const job = await this.request(`/conversations/${this.conversationId}/turns/${message.id}/retry`, {method: 'POST'});
                    this.watch(job.id);
                });
                log.append(retry);
            }
        }
        log.scrollTop = log.scrollHeight;
    }
}

if (!customElements.get('document-research-chat')) customElements.define('document-research-chat', ResearchChatWidget);

export function mount(options = {}) {
    const existing = document.querySelector('document-research-chat');
    if (existing) return existing.configure(options);
    const widget = new ResearchChatWidget().configure(options);
    document.body.append(widget);
    return widget;
}
