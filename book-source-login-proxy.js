// book-source-login-proxy.js
// 直接调用大灰狼书源登录API - 无需执行56KB JS

const http = require('http');
const https = require('https');
const url = require('url');

const PORT = 5013;
const sessions = new Map();

// 大灰狼书源默认服务器列表
const DEFAULT_SERVERS = [
    'https://svip.langge.cf',
    'https://sy.langge.cf', 
    'https://api.langge.cf',
    'http://219.154.201.122',
];

function httpRequest(method, urlStr, body, headers) {
    return new Promise((resolve, reject) => {
        const u = new URL(urlStr);
        const client = u.protocol === 'https:' ? https : http;
        const options = {
            method, hostname: u.hostname, port: u.port,
            path: u.pathname + u.search,
            headers: headers || { 'Content-Type': 'application/json' },
            timeout: 15000,
        };
        const req = client.request(options, res => {
            const setCookie = res.headers['set-cookie'] || [];
            let d = '';
            res.on('data', c => d += c);
            res.on('end', () => resolve({ status: res.statusCode, body: d, cookies: setCookie }));
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        if (body) req.write(typeof body === 'string' ? body : JSON.stringify(body));
        req.end();
    });
}

const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Access-Control-Allow-Origin', '*');
    if (req.method === 'OPTIONS') { res.writeHead(200); res.end(); return; }

    const path = new URL(req.url, `http://localhost:${PORT}`).pathname;
    let body = '';
    req.on('data', c => body += c);
    
    req.on('end', async () => {
        let json = {};
        try { json = JSON.parse(body); } catch {}

        try {
            if (path === '/health') return send(res, 200, { ok: true, sessions: sessions.size });

            // === 调试：探测服务器 ===
            if (path === '/probe') {
                const server = json.server || DEFAULT_SERVERS[0];
                const results = {};
                for (const p of ['/login_api', '/api/login', '/login', '/']) {
                    try {
                        const r = await httpRequest('GET', `${server}${p}`, null, {
                            'User-Agent': 'Mozilla/5.0',
                        });
                        results[p] = `${r.status} ${(r.body||'').slice(0, 100)}`;
                    } catch(e) { results[p] = e.message; }
                }
                return send(res, 200, results);
            }

            // === 登录 API ===
            if (path === '/login' && req.method === 'POST') {
                const { email, password, key, server } = json;
                if (!email && !key) return send(res, 400, { error: '请提供 email 或 key' });

                // === key 登录：直接作为 token，不需要调 API ===
                if (key && !email) {
                    const deviceId = 'reader-' + Math.random().toString(36).slice(2, 10);
                    const cookieStr = `qttoken=${key};deviceId=${deviceId}`;
                    sessions.set(key, {
                        server: server || DEFAULT_SERVERS[0],
                        cookies: [cookieStr],
                        token: key,
                        loginTime: new Date().toISOString(),
                    });
                    return send(res, 200, {
                        success: true,
                        server: server || DEFAULT_SERVERS[0],
                        cookieString: cookieStr,
                        token: key,
                    });
                }

                // === 邮箱密码登录 ===

                const baseUrl = server || DEFAULT_SERVERS[0];
                const deviceKey = 'reader-' + Math.random().toString(36).slice(2, 10);

                // 尝试每个服务器
                const servers = server ? [server] : [...DEFAULT_SERVERS];
                let lastError = '';
                
                for (const srv of servers) {
                    try {
                        console.log(`尝试登录: ${srv}`);
                        const loginBody = {
                            register_email: email || '',
                            password: password || '',
                        };

                        const headers = {
                            'Content-Type': 'application/json',
                            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36',
                        };
                        const result = await httpRequest('POST', `${srv}/login_api`, loginBody, headers);
                        const data = JSON.parse(result.body);

                        if (data.code === 0) {
                            const token = data.key || data.data?.key || '';
                            const deviceId = 'reader-' + Math.random().toString(36).slice(2, 10);
                            const cookieStr = `qttoken=${token};deviceId=${deviceId}`;
                            sessions.set(email, {
                                server: srv, cookies: result.cookies, token,
                                cookieStr, loginTime: new Date().toISOString(),
                            });
                            return send(res, 200, {
                                success: true, server: srv,
                                cookies: result.cookies, cookieString: cookieStr, token,
                            });
                        }
                        lastError = data.msg || `code=${data.code}`;
                        console.log(`  ${srv}: ${lastError}`);
                    } catch (e) {
                        lastError = e.message;
                        console.log(`  ${srv}: 连接失败 - ${e.message}`);
                    }
                }
                return send(res, 401, { success: false, error: lastError || '所有服务器登录失败' });
            }

            // === 检查状态 ===
            if (path === '/status') {
                const key = json.email || json.key || new URL(req.url, `http://localhost:${PORT}`).searchParams.get('email');
                const s = sessions.get(key);
                return send(res, 200, {
                    loggedIn: !!s,
                    server: s?.server || '',
                    loginTime: s?.loginTime || '',
                    cookieString: s?.cookies?.join('; ') || '',
                });
            }

            // === 登出 ===
            if (path === '/logout') {
                sessions.delete(json.email || json.key);
                return send(res, 200, { ok: true });
            }

            send(res, 404, { error: 'Not found' });
        } catch (e) {
            console.error(e);
            send(res, 500, { error: e.message });
        }
    });
});

function send(res, code, data) {
    res.writeHead(code);
    res.end(JSON.stringify(data, null, 2));
}

server.listen(PORT, () => {
    console.log('登录代理已启动: http://localhost:' + PORT);
    console.log('  POST /login  - 登录 (参数: email, password, key, server)');
    console.log('  GET  /status - 检查状态 (参数: email)');
    console.log('  POST /logout - 登出');
    console.log('  GET  /health - 健康检查');
    console.log('服务器列表:', DEFAULT_SERVERS.join(', '));
});