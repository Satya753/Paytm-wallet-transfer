import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import './styles.css';

// Vite replaces this value while building.  Locally, the nginx proxy continues
// to serve the API under /api; the deployed static site uses its public API URL.
const apiBaseUrl = (import.meta.env.VITE_API_URL || '/api').replace(/\/$/, '');
const initial = { token: 'alice-token', from: '', to: '', amount_paise: '', idempotency_key: crypto.randomUUID() };
function App() {
  const [form, setForm] = useState(initial); const [credit, setCredit] = useState({amount_paise: '', idempotency_key: crypto.randomUUID()}); const [wallet, setWallet] = useState(null); const [result, setResult] = useState(null); const [error, setError] = useState('');
  const set = e => setForm({...form, [e.target.name]: e.target.value});
  async function request(path, options={}) {
    setError(''); const response = await fetch(apiBaseUrl + path, { ...options, headers: {'Authorization': `Bearer ${form.token}`, 'Content-Type': 'application/json', ...(options.headers || {})} });
    const json = await response.json(); if (!response.ok) throw new Error(json.error || 'Request failed'); return json;
  }
  async function openWallet() { try { const found = await request('/wallets', {method:'POST'}); setWallet(found); setForm({...form, from: found.upi_id}); } catch(e) {setError(e.message)} }
  async function addBalance(e) { e.preventDefault(); if (!wallet) return; try { const out = await request(`/wallets/${encodeURIComponent(wallet.upi_id)}/credits`, {method:'POST', body: JSON.stringify({...credit, amount_paise: Number(credit.amount_paise)})}); setResult(out); setWallet(await request(`/wallets/${encodeURIComponent(wallet.upi_id)}`)); setCredit({amount_paise: '', idempotency_key: crypto.randomUUID()}); } catch(e) {setError(e.message)} }
  async function transfer(e) { e.preventDefault(); try { const out = await request('/transfers', {method:'POST', body: JSON.stringify({...form, amount_paise: Number(form.amount_paise)})}); setResult(out); setForm({...form, idempotency_key: crypto.randomUUID()}); } catch(e) {setError(e.message)} }
  return <main><header><span>◒</span><div><h1>Wallet</h1><p>Peer-to-peer transfers in integer paise</p></div></header>
    <section className="card"><label>Bearer token<input name="token" value={form.token} onChange={set}/></label><button onClick={openWallet}>Get my wallet</button>{wallet && <div className="balance">UPI ID <code>{wallet.upi_id}</code><strong>{wallet.balance_paise.toLocaleString()} paise</strong></div>}</section>
    {wallet && <section className="card"><h2>Add balance</h2><form onSubmit={addBalance}><label>Amount (paise)<input required min="1" step="1" type="number" value={credit.amount_paise} onChange={e => setCredit({...credit, amount_paise:e.target.value})}/></label><label>Idempotency key<input required value={credit.idempotency_key} onChange={e => setCredit({...credit, idempotency_key:e.target.value})}/></label><button>Add balance</button></form></section>}
    <section className="card"><h2>Send money</h2><form onSubmit={transfer}><label>From UPI ID<input required name="from" placeholder="alice@wallet" value={form.from} onChange={set}/></label><label>To UPI ID<input required name="to" placeholder="bob@wallet" value={form.to} onChange={set}/></label><label>Amount (paise)<input required min="1" step="1" type="number" name="amount_paise" value={form.amount_paise} onChange={set}/></label><label>Idempotency key<input required name="idempotency_key" value={form.idempotency_key} onChange={set}/></label><button>Transfer</button></form>{error && <p className="error">{error}</p>}{result && <pre>{JSON.stringify(result, null, 2)}</pre>}</section></main>
}
createRoot(document.getElementById('root')).render(<App/>);
