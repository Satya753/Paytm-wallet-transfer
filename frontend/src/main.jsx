import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import './styles.css';

const initial = { token: 'alice-token', from: '', to: '', amount_paise: '', idempotency_key: crypto.randomUUID() };
function App() {
  const [form, setForm] = useState(initial); const [wallet, setWallet] = useState(null); const [result, setResult] = useState(null); const [error, setError] = useState('');
  const set = e => setForm({...form, [e.target.name]: e.target.value});
  async function request(path, options={}) {
    setError(''); const response = await fetch('/api' + path, { ...options, headers: {'Authorization': `Bearer ${form.token}`, 'Content-Type': 'application/json', ...(options.headers || {})} });
    const json = await response.json(); if (!response.ok) throw new Error(json.error || 'Request failed'); return json;
  }
  async function openWallet() { try { setWallet(await request('/wallets', {method:'POST'})); } catch(e) {setError(e.message)} }
  async function transfer(e) { e.preventDefault(); try { const out = await request('/transfers', {method:'POST', body: JSON.stringify({...form, amount_paise: Number(form.amount_paise)})}); setResult(out); setForm({...form, idempotency_key: crypto.randomUUID()}); } catch(e) {setError(e.message)} }
  return <main><header><span>◒</span><div><h1>Wallet</h1><p>Peer-to-peer transfers in integer paise</p></div></header>
    <section className="card"><label>Bearer token<input name="token" value={form.token} onChange={set}/></label><button onClick={openWallet}>Get my wallet</button>{wallet && <div className="balance">Wallet <code>{wallet.id}</code><strong>{wallet.balance_paise.toLocaleString()} paise</strong></div>}</section>
    <section className="card"><h2>Send money</h2><form onSubmit={transfer}><label>From wallet<input required name="from" value={form.from} onChange={set}/></label><label>To wallet<input required name="to" value={form.to} onChange={set}/></label><label>Amount (paise)<input required min="1" step="1" type="number" name="amount_paise" value={form.amount_paise} onChange={set}/></label><label>Idempotency key<input required name="idempotency_key" value={form.idempotency_key} onChange={set}/></label><button>Transfer</button></form>{error && <p className="error">{error}</p>}{result && <pre>{JSON.stringify(result, null, 2)}</pre>}</section></main>
}
createRoot(document.getElementById('root')).render(<App/>);
