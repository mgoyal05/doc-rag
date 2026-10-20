import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

const {default: puppeteer} = await import(process.env.RESEARCH_PUPPETEER_PATH ? pathToFileURL(process.env.RESEARCH_PUPPETEER_PATH).href : 'puppeteer');

test('chat widget: safe rendering, uploads, SSE, cancellation, continuity and keyboard/mobile layout', async () => {
    let messages = [], active = null, requests = [], streams = new Set();
    const module = await readFile(new URL('../src/main/resources/META-INF/resources/document-research/chat-widget.js', import.meta.url));
    const loader = await readFile(new URL('../../spring-boot/src/main/resources/META-INF/resources/js/research-chat-loader.js', import.meta.url));
    const server = createServer(async (req, res) => {
        let body = '';
        for await(const bytes of req) body += bytes;
        requests.push({url: req.url, method: req.method, csrf: req.headers['x-csrf'], body});
        res.setHeader('Cache-Control', 'no-store');
        if (req.url === '/document-research/chat-widget.js') {
            res.setHeader('Content-Type', 'text/javascript');
            return res.end(module);
        }
        if (req.url === '/js/research-chat-loader.js') {
            res.setHeader('Content-Type', 'text/javascript');
            return res.end(loader);
        }
        if (req.url === '/') {
            res.setHeader('Content-Type', 'text/html');
            return res.end('<title>Widget fixture</title><aside style="transform:translateX(-100%);visibility:hidden"><script type="module" src="/js/research-chat-loader.js"></script></aside>');
        }
        if (req.url.endsWith('/events')) {
            res.writeHead(200, {'Content-Type': 'text/event-stream'});
            // Like the real subscribe API, send the current snapshot even if completion preceded connection.
            const state = active ? 'PROCESSING' : messages.at(-1)?.state === 'INTERRUPTED' ? 'CANCELLED' : 'COMPLETED';
            res.write(`event: job\ndata: ${JSON.stringify({state})}\n\n`);
            streams.add(res);
            res.on('close', () => streams.delete(res));
            return;
        }
        res.setHeader('Content-Type', 'application/json');
        const send = value => res.end(JSON.stringify(value));
        if (req.url.endsWith('/chat/config')) return send({
            storageKey: 'fixture-conversation',
            csrfHeader: 'X-CSRF',
            csrfToken: 'fixture-token',
            maxFileBytes: 50000
        });
        if (req.url === '/api/research/conversations' && req.method === 'POST') return send({
            id: 'conv-1',
            title: 'Fixture'
        });
        if (req.url === '/api/research/conversations') return send([{id: 'conv-1', title: 'Fixture'}]);
        if (req.url.endsWith('/messages')) {
            const input = JSON.parse(body);
            messages.push({id: 'turn-1', text: input.text, state: 'QUEUED', result: null});
            active = 'job-1';
            return send({id: active});
        }
        if (req.url.endsWith('/documents')) {
            messages.push({id: 'upload-1', text: 'Uploaded fixture.pdf', state: 'QUEUED', result: null});
            active = 'job-2';
            return send({id: active});
        }
        if (req.url.endsWith('/cancel')) {
            active = null;
            messages.at(-1).state = 'INTERRUPTED';
            for (const stream of streams) stream.write('event: job\ndata: {"state":"CANCELLED"}\n\n');
            return send({state: 'CANCELLED'});
        }
        if (req.url === '/api/research/conversations/conv-1') return send({
            conversation: {
                id: 'conv-1',
                activeTurn: null
            }, messages, activeJob: active
        });
        res.statusCode = 404;
        send({error: 'Fixture resource not found'});
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const url = `http://127.0.0.1:${server.address().port}`;
    const browser = await puppeteer.launch({headless: true, args: ['--no-sandbox']});
    const page = await browser.newPage();
    const q = selector => page.evaluate(selector => document.querySelector('document-research-chat').shadowRoot.querySelector(selector)?.textContent, selector);
    try {
        await page.goto(url);
        await page.waitForSelector('document-research-chat');
        await page.evaluate(() => window.widget = document.querySelector('document-research-chat'));
        assert.ok(await page.evaluate(() => window.widget.parentElement === document.body && window.widget.el('.launcher').getBoundingClientRect().width > 0));
        assert.ok(requests.some(r => r.url === '/js/research-chat-loader.js'));
        assert.ok(requests.some(r => r.url === '/document-research/chat-widget.js'));
        await page.evaluate(() => window.widget.open());
        assert.match(await q('.notice'), /shared with authenticated/);
        await page.evaluate(() => {
            const box = window.widget.el('textarea');
            box.value = '<img src=x onerror="window.pwned=true">';
            box.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
        });
        await page.waitForFunction(() => window.widget.jobId === 'job-1');
        assert.equal(requests.find(r => r.url.endsWith('/messages')).csrf, 'fixture-token');
        messages[0].state = 'COMPLETED';
        messages[0].result = {
            message: 'Research scope',
            answer: {
                claims: [{text: '<script>window.pwned=true</script>', citations: ['chunk-1']}],
                evidence: [{
                    id: 'chunk-1',
                    company: 'ACME',
                    pages: [1, 2],
                    page: 1,
                    text: 'Revenue: 125',
                    source: 'javascript:alert(1)'
                }]
            }
        };
        active = null;
        for (const stream of streams) stream.write('event: job\ndata: {"state":"COMPLETED"}\n\n');
        await page.waitForFunction(() => window.widget.el('details'));
        assert.equal(await page.evaluate(() => window.pwned), undefined);
        assert.equal(await page.evaluate(() => window.widget.el('.messages').querySelectorAll('script,img,a').length), 0);
        assert.match(await q('summary'), /pages 1, 2/);
        await page.evaluate(() => {
            const dt = new DataTransfer();
            dt.items.add(new File(['%PDF-fixture'], 'fixture.pdf', {type: 'application/pdf'}));
            const input = window.widget.el('input');
            input.files = dt.files;
            input.dispatchEvent(new Event('change'));
        });
        await page.waitForFunction(() => window.widget.jobId === 'job-2');
        await page.evaluate(() => window.widget.el('.cancel').click());
        await page.waitForFunction(() => !window.widget.jobId);
        assert.match(await q('.messages'), /Retry this turn/);
        assert.deepEqual(await page.evaluate(() => Object.entries(localStorage)), [['fixture-conversation', 'conv-1']]);
        await page.reload();
        await page.waitForSelector('document-research-chat');
        await page.evaluate(() => window.widget = document.querySelector('document-research-chat'));
        await page.evaluate(() => window.widget.open());
        assert.equal(await page.evaluate(() => window.widget.conversationId), 'conv-1');
        await page.keyboard.press('Escape');
        assert.equal(await page.evaluate(() => window.widget.el('.panel').hidden), true);
        assert.equal(await page.evaluate(() => window.widget.shadowRoot.activeElement === window.widget.el('.launcher')), true);
        await page.setViewport({width: 375, height: 812});
        await page.evaluate(() => window.widget.open());
        assert.ok(await page.evaluate(() => window.widget.el('.panel').getBoundingClientRect().right <= innerWidth));
        await page.evaluate(() => window.widget.destroy());
        assert.equal(await page.$('document-research-chat'), null);
    } finally {
        await browser.close();
        for (const stream of streams) stream.end();
        await new Promise(resolve => server.close(resolve));
    }
});
