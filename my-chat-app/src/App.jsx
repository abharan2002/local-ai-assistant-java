import React, { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm    from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { FaSearch, FaSpinner } from 'react-icons/fa';
import './App.css';

export default function App() {
  const [prompt,       setPrompt      ] = useState('');
  const [searchQuery,  setSearchQuery ] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);
  const [messages,     setMessages    ] = useState([]);
  const [isLoading,    setIsLoading   ] = useState(false);
  const [darkMode,     setDarkMode    ] = useState(false);
  const [copyState,    setCopyState   ] = useState({});
  const evtSourceRef = useRef(null);
  const bottomRef    = useRef(null);

  // Auto-scroll on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Load & persist dark mode
  useEffect(() => {
    const saved = localStorage.getItem('darkMode');
    if (saved !== null) setDarkMode(saved === 'true');
    else setDarkMode(window.matchMedia('(prefers-color-scheme: dark)').matches);
  }, []);
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', darkMode ? 'dark' : '');
    localStorage.setItem('darkMode', darkMode);
  }, [darkMode]);

  // Syntax highlighting
  useEffect(() => {
    import('prismjs').then(Prism => Prism.highlightAll());
  }, [messages, darkMode]);

  // Helpers to manage messages
  const injectMessage = msg => setMessages(m => [...m, msg]);
  const updateLast    = txt => setMessages(m => {
    if (m.length === 0) return m;
    const copy = [...m];
    copy[copy.length - 1] = { ...copy[copy.length - 1], text: txt };
    return copy;
  });
  const finishStream = full => {
    updateLast(full);
    setIsLoading(false);
    evtSourceRef.current?.close();
    evtSourceRef.current = null;
  };
  const stopResponse = () => {
    evtSourceRef.current?.close();
    evtSourceRef.current = null;
    setIsLoading(false);
  };

  // Core submit logic for chat vs. web-search
  const handleSubmit = e => {
    e.preventDefault();
    const raw = (isSearchMode ? searchQuery : prompt).trim();
    if (!raw) return;

    injectMessage({ sender:'user', text: raw, id: Date.now() });
    injectMessage({ sender:'ai',   text:'',  id: Date.now() + 1 });
    setIsLoading(true);

    // Choose correct endpoint and query param
    const path     = isSearchMode ? 'web-search'  : 'chat-stream';
    const queryKey = isSearchMode ? 'query'       : 'message';
    const url      = `http://localhost:8080/${path}?${queryKey}=${encodeURIComponent(raw)}&userId=default`;

    evtSourceRef.current?.close();
    const src = new EventSource(url);
    evtSourceRef.current = src;

    let buffer = '';
    src.addEventListener('token', e => {
      buffer += e.data;
      updateLast(buffer);
    });
    src.addEventListener('search_results', e => finishStream(e.data));
    src.addEventListener('complete',       e => finishStream(e.data));
    src.onerror = () => finishStream(buffer || 'Error, please try again.');

    // Reset inputs
    if (isSearchMode) {
      setSearchQuery('');
      setIsSearchMode(false);
    } else {
      setPrompt('');
    }
  };

  // Toggle search/chat mode
  const toggleSearch = () => {
    setIsSearchMode(v => !v);
    if (!isSearchMode) {
      setSearchQuery(prompt);
      setPrompt('');
    } else {
      setPrompt(searchQuery);
      setSearchQuery('');
    }
  };

  // Copy code blocks
  const copyToClipboard = (txt, id) => {
    navigator.clipboard.writeText(txt).then(() => {
      setCopyState(s => ({ ...s, [id]: true }));
      setTimeout(() => setCopyState(s => ({ ...s, [id]: false })), 2000);
    });
  };

  return (
    <div className="chat-container">
      <header className="chat-header">
        <h1>🧠 Abby </h1>
        <div className="header-controls">
          <button
            className={`search-toggle${isSearchMode ? ' active' : ''}`}
            onClick={toggleSearch}
            title={isSearchMode ? 'Chat mode' : 'Search mode'}
          ><FaSearch/></button>
          <button
            className="theme-toggle"
            onClick={() => setDarkMode(dm => !dm)}
            title="Toggle theme"
          >
            {darkMode ? '☀️' : '🌙'}
          </button>
        </div>
      </header>

      <div className="message-list">
        {messages.length === 0 && (
          <div className="welcome-message">
            <h2>Welcome!</h2>
            <p>Type a message or switch to Search mode.</p>
          </div>
        )}
        {messages.map(msg => (
          <div key={msg.id} className={`message ${msg.sender}`}>
            <span className="sender-label">
              {msg.sender === 'user' ? 'You' : 'Assistant'}
            </span>
            <div className="message-text">
              {msg.sender === 'user'
                ? <div style={{ whiteSpace:'pre-wrap' }}>{msg.text}</div>
                : <ReactMarkdown
                    remarkPlugins={[remarkGfm, remarkBreaks]}
                    components={{
                      code({ inline, className, children }) {
                        const m = /language-(\w+)/.exec(className||'');
                        const lang = m ? m[1] : '';
                        const txt  = String(children).trim();
                        const id   = `c-${Math.random().toString(36).slice(2)}`;
                        if (inline || !lang) {
                          return <code className="inline-code">{children}</code>;
                        }
                        return (
                          <div className="code-wrapper">
                            <div className="code-header">
                              <span className="code-language">{lang}</span>
                              <button
                                className="copy-button"
                                onClick={() => copyToClipboard(txt, id)}
                              >
                                {copyState[id] ? 'Copied!' : 'Copy'}
                              </button>
                            </div>
                            <pre className={`language-${lang}`}>
                              <code className={`language-${lang}`}>{children}</code>
                            </pre>
                          </div>
                        );
                      }
                    }}
                  >{msg.text}</ReactMarkdown>
              }
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {isLoading && (
        <div className="loading-indicator">
          <FaSpinner className="spinner" /> Abby is thinking...
        </div>
      )}

      <form className="message-form" onSubmit={handleSubmit}>
        <input
          value={isSearchMode ? searchQuery : prompt}
          onChange={e => isSearchMode
            ? setSearchQuery(e.target.value)
            : setPrompt(e.target.value)
          }
          placeholder={isSearchMode ? 'Search the web…' : 'Type a message…'}
          disabled={isLoading}
        />
        {isLoading ? (
          <button type="button" className="stop-button" onClick={stopResponse}>
            Stop
          </button>
        ) : (
          <button
            type="submit"
            className="send-button"
            disabled={!(isSearchMode ? searchQuery.trim() : prompt.trim())}
            title={isSearchMode ? 'Search' : 'Send'}
          >
            {isSearchMode ? <FaSearch/> : '→'}
          </button>
        )}
      </form>
    </div>
  );
}
