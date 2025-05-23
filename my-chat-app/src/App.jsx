import React, { useState, useRef, useEffect, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './App.css';

const App = () => {
  const [prompt, setPrompt] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [copyState, setCopyState] = useState({});
  const [conversations, setConversations] = useState([
    { id: 1, title: 'New conversation', active: true, timestamp: new Date() }
  ]);
  
  const bottomRef = useRef(null);
  const evtSourceRef = useRef(null);
  const textareaRef = useRef(null);
  const fileInputRef = useRef(null);

  // Auto-scroll on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Dark mode persistence
  useEffect(() => {
    const saved = localStorage.getItem('darkMode');
    if (saved !== null) {
      setDarkMode(saved === 'true');
    }
  }, []);

  useEffect(() => {
    localStorage.setItem('darkMode', darkMode);
  }, [darkMode]);

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    }
  }, [prompt, searchQuery]);

  // Message management helpers
  const injectMessage = useCallback(msg => setMessages(m => [...m, msg]), []);
  const updateLast = useCallback(txt => setMessages(m => {
    if (m.length === 0) return m;
    const copy = [...m];
    copy[copy.length - 1] = { ...copy[copy.length - 1], text: txt };
    return copy;
  }), []);
  
  const finishStream = useCallback(full => {
    updateLast(full);
    setIsLoading(false);
    evtSourceRef.current?.close();
    evtSourceRef.current = null;
  }, [updateLast]);
  
  const stopResponse = useCallback(() => {
    evtSourceRef.current?.close();
    evtSourceRef.current = null;
    setIsLoading(false);
  }, []);

  // Submit handler with all functionalities
  const handleSubmit = async (e) => {
    e.preventDefault();
    const raw = (isSearchMode ? searchQuery : prompt).trim();
    if (!raw && !selectedFile) return;

    let messageText = raw;
    if (selectedFile) {
      messageText += ` [File: ${selectedFile.name}]`;
    }

    const userMessage = { 
      sender: 'user', 
      text: messageText, 
      id: Date.now(), 
      timestamp: new Date(),
      file: selectedFile 
    };
    
    injectMessage(userMessage);
    injectMessage({ sender: 'ai', text: '', id: Date.now() + 1, timestamp: new Date() });
    setIsLoading(true);

    try {
      if (selectedFile) {
        // Handle file upload
        await handleFileUpload(selectedFile, raw);
      } else {
        // Handle chat or web search
        const path = isSearchMode ? 'web-search' : 'chat-stream';
        const queryKey = isSearchMode ? 'query' : 'message';
        const url = `http://localhost:8080/${path}?${queryKey}=${encodeURIComponent(raw)}&userId=default`;

        evtSourceRef.current?.close();
        const src = new EventSource(url);
        evtSourceRef.current = src;

        let buffer = '';
        
        src.addEventListener('token', e => {
          try {
            buffer += e.data;
            updateLast(buffer);
          } catch (err) {
            console.error("Error handling token event:", err);
          }
        });
        
        src.addEventListener('search_results', e => {
          try {
            finishStream(e.data);
          } catch (err) {
            console.error("Error handling search_results event:", err);
            finishStream("Error processing search results. Please try again.");
          }
        });
        
        src.addEventListener('complete', e => {
          try {
            finishStream(e.data);
          } catch (err) {
            console.error("Error handling complete event:", err);
            finishStream("Error completing response. Please try again.");
          }
        });
        
        src.addEventListener('error', e => {
          try {
            let errorMsg = "An error occurred";
            if (e.data) errorMsg = e.data;
            console.error("SSE error event:", errorMsg);
            finishStream(errorMsg);
          } catch (err) {
            console.error("Error handling error event:", err);
            finishStream("Unknown error occurred. Please try again.");
          }
        });
        
        src.onerror = (err) => {
          console.error("SSE connection error:", err);
          finishStream(buffer || 'Connection error, please try again.');
        };
      }
    } catch (error) {
      console.error('Submit error:', error);
      finishStream('Error sending message. Please try again.');
    }

    // Reset inputs
    if (isSearchMode) {
      setSearchQuery('');
    } else {
      setPrompt('');
    }
    setSelectedFile(null);
  };

  // File upload handler
  const handleFileUpload = async (file, message) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('message', message);
    formData.append('userId', 'default');

    try {
      const response = await fetch('http://localhost:8080/upload-file', {
        method: 'POST',
        body: formData,
      });

      if (!response.ok) throw new Error('Upload failed');
      
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        
        const chunk = decoder.decode(value);
        buffer += chunk;
        updateLast(buffer);
      }
      
      finishStream(buffer);
    } catch (error) {
      finishStream('Error uploading file: ' + error.message);
    }
  };

  // Conversation management
  const startNewConversation = () => {
    const newId = Date.now();
    const newConv = { 
      id: newId, 
      title: 'New conversation', 
      active: true, 
      timestamp: new Date() 
    };
    
    setConversations(prev => prev.map(c => ({...c, active: false})).concat([newConv]));
    setMessages([]);
    setSidebarOpen(false);
  };

  const deleteConversation = (id) => {
    setConversations(prev => prev.filter(c => c.id !== id));
    if (conversations.find(c => c.id === id)?.active) {
      setMessages([]);
    }
  };

  // Copy message
  const copyMessage = (text, id) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopyState(s => ({ ...s, [id]: true }));
      setTimeout(() => setCopyState(s => ({ ...s, [id]: false })), 2000);
    });
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

  // Export conversation
  const exportConversation = () => {
    const conversationData = {
      title: conversations.find(c => c.active)?.title || 'Conversation',
      messages: messages,
      timestamp: new Date().toISOString()
    };
    
    const blob = new Blob([JSON.stringify(conversationData, null, 2)], {
      type: 'application/json'
    });
    
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${conversationData.title}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Handle key press
  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const currentInput = isSearchMode ? searchQuery : prompt;
  const setCurrentInput = isSearchMode ? setSearchQuery : setPrompt;

  return (
    <div className={`app ${darkMode ? 'dark' : ''}`}>
      {/* Sidebar */}
      <div className={`sidebar ${sidebarOpen ? 'open' : ''}`}>
        <div className="sidebar-header">
          <h2>💬 Abby AI</h2>
          <button onClick={() => setSidebarOpen(false)}>×</button>
        </div>
        
        <button className="new-chat" onClick={startNewConversation}>
          + New Chat
        </button>
        
        <div className="chat-list">
          {conversations.map(conv => (
            <div key={conv.id} className={`chat-item ${conv.active ? 'active' : ''}`}>
              <div className="chat-content">
                <span className="chat-title">{conv.title}</span>
                <span className="chat-date">{conv.timestamp.toLocaleDateString()}</span>
              </div>
              <button 
                className="delete-chat-btn"
                onClick={() => deleteConversation(conv.id)}
                title="Delete conversation"
              >
                🗑️
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Main Area */}
      <div className="main">
        {/* Header */}
        <div className="header">
          <div className="header-left">
            <button className="menu-btn" onClick={() => setSidebarOpen(true)}>
              ☰
            </button>
            <h1>Abby AI Assistant</h1>
          </div>
          
          <div className="header-right">
            <button 
              className={`search-btn ${isSearchMode ? 'active' : ''}`}
              onClick={toggleSearch}
              title={isSearchMode ? 'Switch to Chat Mode' : 'Switch to Search Mode'}
            >
              🔍 {isSearchMode ? 'Search' : 'Chat'}
            </button>
            <button className="export-btn" onClick={exportConversation} title="Export conversation">
              📥
            </button>
            <button className="theme-btn" onClick={() => setDarkMode(!darkMode)}>
              {darkMode ? '☀️' : '🌙'}
            </button>
          </div>
        </div>

        {/* Messages */}
        <div className="messages-container">
          {messages.length === 0 ? (
            <div className="welcome">
              <h2>👋 Welcome to Abby AI</h2>
              <p>{isSearchMode ? 'Search the web for information' : 'How can I help you today?'}</p>
              
              <div className="examples">
                {isSearchMode ? (
                  <>
                    <button onClick={() => setCurrentInput("Latest news in AI")}>
                      Latest news in AI
                    </button>
                    <button onClick={() => setCurrentInput("Weather forecast today")}>
                      Weather forecast today
                    </button>
                    <button onClick={() => setCurrentInput("Best restaurants nearby")}>
                      Best restaurants nearby
                    </button>
                    <button onClick={() => setCurrentInput("Stock market updates")}>
                      Stock market updates
                    </button>
                  </>
                ) : (
                  <>
                    <button onClick={() => setCurrentInput("Explain quantum computing")}>
                      Explain quantum computing
                    </button>
                    <button onClick={() => setCurrentInput("Write a React component")}>
                      Write a React component
                    </button>
                    <button onClick={() => setCurrentInput("Plan a trip to Japan")}>
                      Plan a trip to Japan
                    </button>
                    <button onClick={() => setCurrentInput("Create a bedtime story")}>
                      Create a bedtime story
                    </button>
                  </>
                )}
              </div>
            </div>
          ) : (
            <div className="messages">
              {messages.map(msg => (
                <div key={msg.id} className={`message ${msg.sender}`}>
                  <div className="avatar">
                    {msg.sender === 'user' ? '👤' : '🤖'}
                  </div>
                  <div className="content">
                    <div className="message-header">
                      <span className="name">
                        {msg.sender === 'user' ? 'You' : 'Abby'}
                      </span>
                      {msg.timestamp && (
                        <span className="time">
                          {msg.timestamp.toLocaleTimeString()}
                        </span>
                      )}
                    </div>
                    <div className="text">
                      {msg.sender === 'user' ? (
                        msg.text
                      ) : (
                        <ReactMarkdown 
                          remarkPlugins={[remarkGfm]}
                          components={{
                            code({ inline, className, children }) {
                              const match = /language-(\w+)/.exec(className || '');
                              const lang = match ? match[1] : '';
                              const text = String(children).trim();
                              
                              if (inline) {
                                return <code className="inline-code">{children}</code>;
                              }
                              
                              return (
                                <div className="code-block">
                                  <div className="code-header">
                                    <span>{lang || 'code'}</span>
                                    <button
                                      className="copy-btn"
                                      onClick={() => copyMessage(text, msg.id)}
                                    >
                                      {copyState[msg.id] ? '✅' : '📋'}
                                    </button>
                                  </div>
                                  <pre><code>{children}</code></pre>
                                </div>
                              );
                            }
                          }}
                        >
                          {msg.text}
                        </ReactMarkdown>
                      )}
                    </div>
                    <div className="message-actions">
                      <button
                        className="copy-message-btn"
                        onClick={() => copyMessage(msg.text, msg.id)}
                        title="Copy message"
                      >
                        {copyState[msg.id] ? '✅' : '📋'}
                      </button>
                    </div>
                  </div>
                </div>
              ))}
              
              {isLoading && (
                <div className="loading">
                  <div className="loading-dots">
                    <span>●</span>
                    <span>●</span>
                    <span>●</span>
                  </div>
                  <span>Thinking...</span>
                  <button onClick={stopResponse} className="stop-btn">
                    Stop
                  </button>
                </div>
              )}
              <div ref={bottomRef} />
            </div>
          )}
        </div>

        {/* Input */}
        <div className="input-container">
          {selectedFile && (
            <div className="file-preview">
              📎 {selectedFile.name}
              <button onClick={() => setSelectedFile(null)}>×</button>
            </div>
          )}
          
          <form onSubmit={handleSubmit} className="input-form">
            <button 
              type="button"
              className="attach-btn"
              onClick={() => fileInputRef.current?.click()}
              title="Attach file"
            >
              📎
            </button>
            
            <textarea
              ref={textareaRef}
              value={currentInput}
              onChange={(e) => setCurrentInput(e.target.value)}
              placeholder={isSearchMode ? 'Search the web...' : 'Type your message...'}
              rows="1"
              onKeyDown={handleKeyDown}
              disabled={isLoading}
            />
            
            <button 
              type="submit" 
              disabled={!currentInput.trim() && !selectedFile}
              className="send-btn"
            >
              {isSearchMode ? '🔍' : '➤'}
            </button>
          </form>
          
          <input
            ref={fileInputRef}
            type="file"
            style={{ display: 'none' }}
            onChange={(e) => setSelectedFile(e.target.files[0])}
            accept=".txt,.pdf,.doc,.docx,.jpg,.jpeg,.png,.md,.csv,.json"
          />
          
          <div className="input-footer">
            <small>
              {isSearchMode 
                ? 'Search mode: Get real-time information from the web' 
                : 'Chat mode: Have a conversation with Abby AI'
              }
            </small>
          </div>
        </div>
      </div>

      {/* Overlay */}
      {sidebarOpen && <div className="overlay" onClick={() => setSidebarOpen(false)} />}
    </div>
  );
};

export default App;
