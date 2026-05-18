// book-source-login-proxy.js
// 零依赖，纯 Node.js 内置模块
// node book-source-login-proxy.js

const http = require('http');
const https = require('https');
const vm = require('vm');

const PORT = 5013;
const sessions = new Map();

function httpGet(urlStr) {
    return new Promise(resolve => {
        const get = urlStr.startsWith('https') ? https.get : http.get;
        const req = get(urlStr, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => resolve(d));
        });
        req.on('error', () => resolve(''));
        req.setTimeout(10000, () => { req.destroy(); resolve(''); });
    });
}

function createBridge(sourceUrl) {
    const s = sessions.get(sourceUrl) || { variable: '{}', cookies: {} };
    const hostList = [sourceUrl];
    const app = { key: '' };
    
    return {
        java: {
            ajax: u => httpGet(u),
            toast: m => console.log('[Toast]', m),
            longToast: m => console.log('[Toast]', m),
            startBrowser: u => httpGet(u),
            androidId: () => 'reader-proxy',
            deviceID: () => 'reader-proxy',
            base64Encode: s => Buffer.from(s).toString('base64'),
            base64Decode: s => Buffer.from(s, 'base64').toString(),
            qread: () => { throw new Error('not qread'); },
            cacheFile: u => httpGet(u),
        },
        source: {
            getVariable: () => s.variable,
            setVariable: v => { s.variable = v; },
            getLoginInfoMap: () => { try { return JSON.parse(s.variable); } catch { return {}; } },
        },
        cookie: {
            getCookie: u => s.cookies[u] || '',
            setCookie: (u, c) => { s.cookies[u] = c; },
        },
        host: hostList,
        app: app,
    };
}

const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Access-Control-Allow-Origin', '*');
    
    if (req.method === 'OPTIONS') {
        res.writeHead(200); res.end(); return;
    }

    const reqUrl = new URL(req.url, `http://localhost:${PORT}`);
    const path = reqUrl.pathname;

    let body = '';
    req.on('data', c => body += c);

    req.on('end', async () => {
        let json = {};
        try { json = JSON.parse(body); } catch {}

        try {
            // GET /health
            if (path === '/health') {
                return send(res, 200, { ok: true, sessions: sessions.size });
            }

            // GET/POST /status
            if (path === '/status') {
                const su = reqUrl.searchParams.get('sourceUrl') || json.sourceUrl;
                const s = sessions.get(su);
                return send(res, 200, { loggedIn: !!s, cookies: s?.cookies || {} });
            }

            // POST /logout
            if (path === '/logout') {
                sessions.delete(json.sourceUrl);
                return send(res, 200, { ok: true });
            }

            // POST /login
            if (path === '/login' && req.method === 'POST') {
                const { sourceUrl, loginUrl, loginData } = json;
                if (!sourceUrl || !loginUrl) {
                    return send(res, 400, { error: '缺少 sourceUrl 或 loginUrl' });
                }

                // 初始化 session
                if (!sessions.has(sourceUrl)) {
                    sessions.set(sourceUrl, { variable: JSON.stringify(loginData || {}), cookies: {} });
                }

                // 提取 JS
                let js = loginUrl.replace(/<js>/g, '').replace(/<\/js>/g, '').trim();
                if (!js) return send(res, 200, { success: true, msg: '无需执行JS' });

                // 如果 JS 中没有 function/var/return，可能是直接 URL
                if (!js.includes('function') && !js.includes('var ') && !js.includes('return')) {
                    if (js.startsWith('http')) {
                        const result = await httpGet(js);
                        return send(res, 200, { success: true, msg: '网页登录', loginPageUrl: js });
                    }
                }

                // 创建沙箱执行 JS
                const bridge = createBridge(sourceUrl);
                const ctx = vm.createContext({
                    java: bridge.java,
                    source: bridge.source,
                    cookie: bridge.cookie,
                    host: bridge.host,
                    app: bridge.app,
                    JSON: JSON,
                    console: { log: () => {}, error: () => {} },
                    setTimeout: () => {},
                });

                try {
                    const result = vm.runInContext(js, ctx, { timeout: 30000 });
                    const s = sessions.get(sourceUrl);
                    return send(res, 200, {
                        success: true,
                        msg: '登录执行完成',
                        cookies: s?.cookies || {},
                        variable: s?.variable || '{}',
                        result: typeof result === 'string' ? result.slice(0, 200) : null,
                    });
                } catch (e) {
                    // JS 执行失败可能是正常的（比如依赖浏览器环境）
                    return send(res, 200, {
                        success: true,
                        msg: 'JS 部分执行（可能需要浏览器登录）',
                        error: e.message,
                    });
                }
            }

            send(res, 404, { error: 'Not found' });
        } catch (e) {
            send(res, 500, { error: e.message });
        }
    });
});

function send(res, code, data) {
    res.writeHead(code);
    res.end(JSON.stringify(data, null, 2));
}

server.listen(PORT, () => {
    console.log(`✅ 书源登录代理已启动: http://localhost:${PORT}`);
    console.log(`   POST /login  - 执行书源登录`);
    console.log(`   GET  /status - 检查登录状态`);
    console.log(`   POST /logout - 登出`);
    console.log(`   GET  /health - 健康检查`);
});