// Same response shape as DoclingExtractorTest; validates transport wiring, not OCR accuracy.
const http = require('node:http');
http.createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url !== '/v1/chunk/hybrid/source') {
        res.statusCode = 501;
        return res.end(JSON.stringify({error: 'Endpoint not modeled'}));
    }
    const text = 'ACME quarterly report 2025-01-01/2025-03-31. Revenue 125 INR crore.';
    const page = n => ({page_no: n, size: {width: 612, height: 792}});
    res.end(JSON.stringify({
        chunks: [{
            chunk_index: 0,
            text: 'Financial results\n' + text,
            raw_text: text,
            num_tokens: 40,
            headings: ['Financial results'],
            page_numbers: [1, 2]
        }],
        documents: [{
            status: 'success',
            errors: [],
            content: {
                json_content: {
                    schema_name: 'DoclingDocument',
                    version: '1.7.0',
                    name: 'synthetic',
                    pages: {'1': page(1), '2': page(2)}
                }
            }
        }]
    }));
}).listen(process.env.PORT);
