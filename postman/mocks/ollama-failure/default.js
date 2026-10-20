// The simulation injects HTTP 503 before this deliberately unsupported handler.
require('node:http').createServer((req, res) => {
    res.statusCode = 501;
    res.end('Not modeled');
}).listen(process.env.PORT);
