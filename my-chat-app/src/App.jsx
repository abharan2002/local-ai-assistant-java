import React, { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import './App.css';
import Prism from 'prismjs';
import 'prismjs/themes/prism-tomorrow.css';

function App() {
  const [prompt, setPrompt] = useState('');
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [copyState, setCopyState] = useState({});
  const evtSourceRef = useRef(null);
  const messageListRef = useRef(null);
  const bottomRef = useRef(null);

  // Auto-scroll
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Detect system theme + localStorage
  useEffect(() => {
    const savedMode = localStorage.getItem('darkMode');
    const prefersDark =
      window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;

    if (savedMode !== null) {
      setDarkMode(savedMode === 'true');
    } else {
      setDarkMode(prefersDark);
    }
  }, []);

  // Apply dark mode
  useEffect(() => {
    if (darkMode) {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    localStorage.setItem('darkMode', darkMode);
  }, [darkMode]);

  // Apply syntax highlighting
  useEffect(() => {
    setTimeout(() => {
      Prism.highlightAll();
    }, 50);
  }, [messages]);
  
  // Re-highlight when theme changes
  useEffect(() => {
    setTimeout(() => {
      Prism.highlightAll();
    }, 100);
  }, [darkMode]);

  // Cleanup event source on unmount
  useEffect(() => {
    return () => {
      if (evtSourceRef.current) {
        evtSourceRef.current.close();
      }
    };
  }, []);

  const injectMessage = msg => setMessages(msgs => [...msgs, msg]);

  const updateLastMessage = text => {
    setMessages(msgs => {
      if (msgs.length === 0) return msgs;
      return msgs.map((m, i) => (i === msgs.length - 1 ? { ...m, text } : m));
    });
  };

  const finish = fullText => {
    updateLastMessage(fullText);
    setIsLoading(false);
    evtSourceRef.current?.close();
    evtSourceRef.current = null;
  };

  // Function to stop the current response stream
  const stopResponseStream = () => {
    if (evtSourceRef.current) {
      evtSourceRef.current.close();
      evtSourceRef.current = null;
      setIsLoading(false);
      
      // Optionally add a note that the response was stopped
      updateLastMessage(messages[messages.length - 1].text + " [Response stopped]");
    }
  };

  const handleSubmit = e => {
    e.preventDefault();
    if (!prompt.trim()) return;
    
    // If there's an ongoing stream, stop it first
    if (isLoading && evtSourceRef.current) {
      stopResponseStream();
    }

    injectMessage({ sender: 'user', text: prompt, id: Date.now() });
    const assistantId = Date.now() + 1;
    injectMessage({ sender: 'ai', text: '', id: assistantId });

    setPrompt('');
    setIsLoading(true);

    evtSourceRef.current?.close();

    const url = `http://localhost:8080/chat-stream?message=${encodeURIComponent(prompt)}`;
    const source = new EventSource(url);
    evtSourceRef.current = source;

    let buffer = '';

    source.addEventListener('token', e => {
      buffer += e.data;
      updateLastMessage(buffer);
    });

    source.addEventListener('complete', e => {
      finish(e.data);
    });

    source.onerror = () => {
      finish(buffer || "Sorry, there was an error generating a response. Please try again.");
    };
  };

  const toggleDarkMode = () => {
    setDarkMode(!darkMode);
  };

  const copyToClipboard = (text, id) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopyState({ ...copyState, [id]: true });
      setTimeout(() => {
        setCopyState({ ...copyState, [id]: false });
      }, 2000);
    });
  };

  return (
    <div className="chat-container">
      <header className="chat-header">
        <h1>
          <span role="img" aria-label="brain">🧠</span>Abharan's AI Assistant
        </h1>
        <button className="theme-toggle" onClick={toggleDarkMode} aria-label="Toggle theme">
          {darkMode ? '☀️' : '🌙'}
        </button>
      </header>

      <div className="message-list" ref={messageListRef}>
        {messages.length === 0 && (
          <div className="welcome-message">
            <h2>Welcome to AI Assistant</h2>
            <p>Ask me anything and I'll do my best to help you!</p>
          </div>
        )}
        
        {messages.map(msg => (
          <div key={msg.id} className={`message ${msg.sender}`}>
            <div className="message-content">
              <span className="sender-label">
                {msg.sender === 'user' ? 'You' : 'Assistant'}
              </span>
              <div className="message-text">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm, remarkBreaks]}
                  components={{
                    code({ node, inline, className, children }) {
                      const match = /language-(\w+)/.exec(className || '');
                      const lang = match ? match[1] : '';
                      const codeText = String(children).replace(/\n$/, '');
                      const codeId = `code-${Math.random().toString(36).substr(2, 9)}`;

                      return !inline && match ? (
                        <div className="code-wrapper">
                          <div className="code-header">
                            <span className="code-language">{lang}</span>
                            <button
                              className="copy-button"
                              onClick={() => copyToClipboard(codeText, codeId)}
                            >
                              {copyState[codeId] ? 'Copied!' : 'Copy'}
                            </button>
                          </div>
                          <pre className={`language-${lang}`}>
                            <code className={`language-${lang}`}>{children}</code>
                          </pre>
                        </div>
                      ) : (
                        <code className="inline-code">{children}</code>
                      );
                    },
                  }}
                >
                  {msg.text}
                </ReactMarkdown>
              </div>
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {isLoading && (
        <div className="loading-indicator">
          <div className="typing-dots">
            <div className="typing-dot"></div>
            <div className="typing-dot"></div>
            <div className="typing-dot"></div>
          </div>
          <span>Assistant is thinking...</span>
        </div>
      )}

      <form onSubmit={handleSubmit} className="message-form">
        <div className="message-input-wrapper">
          <input
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            placeholder="Type your message..."
            disabled={isLoading && !evtSourceRef.current}
          />
        </div>
        
        {isLoading ? (
          <button 
            type="button" 
            onClick={stopResponseStream} 
            className="stop-button"
          >
            Stop
          </button>
        ) : (
          <button 
            type="submit" 
            disabled={!prompt.trim()} 
            className="send-button"
          >
            →
          </button>
        )}
      </form>
    </div>
  );
}

export default App;
