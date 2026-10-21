import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

// 仅执行本包固定的集合结构；不替代完整 Postman/Newman 运行时。
const collection = JSON.parse(await readFile(new URL('./DHU-Acceptance.postman_collection.json', import.meta.url), 'utf8'));
const environment = JSON.parse(await readFile(new URL('./DHU-Acceptance.postman_environment.json', import.meta.url), 'utf8'));
const values = new Map(environment.values.filter(v => v.enabled !== false).map(v => [v.key, v.value]));
const args = process.argv.slice(2);
if (args.length) {
  if (args.length !== 2 || args[0] !== '--base-url') throw new Error('Usage: node run-collection.mjs [--base-url http://localhost:8081]');
  values.set('baseUrl', args[1]);
}
const base = new URL(values.get('baseUrl'));
if (base.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) {
  throw new Error('The demonstration runner only accepts a local HTTP backend.');
}
const expand = text => text.replace(/\{\{([^}]+)}}/g, (_, key) => {
  if (!values.has(key) || values.get(key) === '') throw new Error(`Missing environment variable: ${key}`);
  return String(values.get(key));
});
function* requests(items) {
  for (const item of items) {
    if (item.item) yield* requests(item.item);
    else yield item;
  }
}
let cookie = '';
let count = 0;
let assertions = 0;
try {
  for (const item of requests(collection.item)) {
    const url = new URL(expand(typeof item.request.url === 'string' ? item.request.url : item.request.url.raw));
    if (url.origin !== base.origin) throw new Error('Cross-origin request refused');
    const headers = Object.fromEntries((item.request.header ?? []).filter(h => !h.disabled).map(h => [h.key, expand(h.value)]));
    if (cookie) headers.Cookie = cookie;
    const response = await fetch(url, {
      method: item.request.method, headers,
      body: item.request.body ? expand(item.request.body.raw) : undefined,
      redirect: 'error', signal: AbortSignal.timeout(15000)
    });
    const setCookies = response.headers.getSetCookie();
    for (const entry of setCookies) {
      if (/^JSESSIONID=/i.test(entry)) {
        cookie = /max-age=0(?:;|$)/i.test(entry) ? '' : entry.split(';')[0];
      }
    }
    const body = await response.text();
    const scope = { get: key => values.get(key), set: (key, value) => values.set(key, value) };
    const pm = {
      response: { code: response.status, json: () => JSON.parse(body) },
      environment: scope,
      test(name, fn) {
        try { fn(); assertions++; }
        catch (error) { throw new Error(`${item.name}: ${name}: ${error.message}`); }
      }
    };
    for (const event of item.event ?? []) {
      if (event.listen !== 'test') throw new Error('Only response test scripts are supported');
      vm.runInNewContext(event.script.exec.join('\n'), { pm }, { timeout: 1000 });
    }
    console.log(`PASS ${++count}. ${item.name} (${response.status})`);
  }
  console.log(`Completed: ${count} requests, ${assertions} assertions passed.`);
} catch (error) {
  console.error(`FAIL after ${count} requests: ${error.message}`);
  process.exitCode = 1;
}
