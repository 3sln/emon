export const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
class SafeHTML { constructor(value) { this.value = value; } toString() { return this.value; } }
export const raw = value => new SafeHTML(String(value ?? ''));
const safe = value => value instanceof SafeHTML ? value.value : Array.isArray(value) ? value.map(safe).join('') : escape(value);
export function html(strings, ...values) { return raw(strings.reduce((s, part, i) => s + part + (i < values.length ? safe(values[i]) : ''), '')); }
export function rich(pattern, parts = {}) {
  let result = escape(pattern).replace(/`([^`]+)`/g, '<code>$1</code>');
  result = result.replace(/\{([\w-]+)\}/g, (match, key) => key in parts ? safe(parts[key]) : match);
  return raw(result);
}
