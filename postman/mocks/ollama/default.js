// Local synthetic responses matching the pinned LangChain4j/Ollama contract tests. No real financial data.
const http = require('node:http');
http.createServer(async (req, res) => {
    let body = req.body;
    if (!body) {
        let raw = '';
        for await(const part of req) raw += part;
        body = JSON.parse(raw || '{}');
    }
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/api/embed') return res.end(JSON.stringify({model: 'test-model', embeddings: [[1, 0, 0]]}));
    if (req.url !== '/api/chat') {
        res.statusCode = 501;
        return res.end(JSON.stringify({error: 'Endpoint not modeled'}));
    }
    let result;
    const schema = body.format?.properties || {};
    if (schema.company) result = {
        company: 'ACME',
        companyQuote: 'ACME',
        periods: [{label: 'Q1 2025', start: '2025-01-01', end: '2025-03-31', quote: '2025-01-01/2025-03-31'}],
        clarification: ''
    };
    else if (schema.targets) result = {
        targets: [{company: 'ACME', period: '2025-01-01/2025-03-31'}],
        clarification: '',
        standaloneQuestion: body.messages.findLast(m => m.role === 'user').content.split('\nQUESTION: ').at(-1)
    };
    else if (schema.verdicts) {
        if (body.model === 'slow-verifier') await new Promise(resolve => setTimeout(resolve, 1500));
        const prompt = body.messages.findLast(m => m.role === 'user').content;
        const claims = JSON.parse(prompt.slice(prompt.indexOf('CLAIMS:\n') + 8, prompt.indexOf('\nEVIDENCE:\n')));
        result = {
            verdicts: claims.map(claim => ({
                id: claim.id,
                supported: !claim.claim.text.includes('acquired'),
                reason: claim.claim.text.includes('acquired') ? 'UNSUPPORTED' : 'SUPPORTED'
            }))
        };
        if (body.model === 'malformed-verifier') result = {verdicts: []};
    } else {
        const prompt = body.messages.findLast(m => m.role === 'user').content;
        const evidence = JSON.parse(prompt.slice(prompt.indexOf('EVIDENCE:\n') + 10));
        const id = evidence[0].id;
        result = {
            claims: [{text: 'Revenue is {{F1}}.', citations: [id]}],
            facts: [{
                id: 'F1',
                evidenceId: id,
                metric: 'Revenue',
                value: '125',
                currency: 'INR',
                unit: 'crore',
                basis: 'NOT_STATED',
                quote: 'Revenue 125 INR crore.'
            }]
        };
        if (prompt.includes('acquired')) result = {claims: [{text: 'ACME acquired its competitor.', citations: [id]}]};
        if (prompt.includes('literal-quantity')) result = {
            claims: [{
                text: 'Profit is 125 INR crore.',
                citations: [id]
            }]
        };
    }
    res.end(JSON.stringify({
        model: 'test-chat',
        done: true,
        done_reason: 'stop', prompt_eval_count: 100, eval_count: 40, load_duration: 1000000, eval_duration: 2000000,
        message: {role: 'assistant', content: JSON.stringify(result)}
    }));
}).listen(process.env.PORT);
