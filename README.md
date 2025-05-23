# Modern AI Chat Interface

A sleek, responsive chat interface for AI interactions with a focus on user experience and modern design principles. Built with React and designed for seamless integration with any AI backend service.

![AI Chat Interface](https://github.com/abharan2002/local-ai-assistant-java)

- **Elegant UI/UX**: Clean, modern interface with smooth animations and transitions
- **Responsive Design**: Fully adaptive layout that works perfectly across all device sizes
- **Dark/Light Mode**: Polished theme system with automatic system preference detection
- **Code Highlighting**: Beautiful syntax highlighting for code blocks with copy functionality
- **Markdown Support**: Rich text formatting with full markdown capabilities
- **Real-time Streaming**: Supports streaming responses with typing indicator
- **Interrupt Responses**: Stop AI responses mid-generation to ask new questions
- **Accessibility Focused**: Designed with accessibility in mind for all users

## 🚀 Getting Started

### Prerequisites

- Node.js (v16 or higher)
- npm or yarn

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/modern-ai-chat.git

# Navigate to the project directory
cd modern-ai-chat

# Install dependencies
npm install

# Start the development server
npm run dev
```

## 🔧 Configuration

The chat interface is designed to connect to any backend that supports server-sent events (SSE) for streaming responses. Configure your API endpoint in the `handleSubmit` function in `App.jsx`.

```javascript
// Example configuration
const url = `http://your-api-endpoint/chat-stream?message=${encodeURIComponent(prompt)}`;
```

## 🎨 Customization

The UI is built with CSS variables for easy customization. Edit the `:root` and `[data-theme="dark"]` variables in `App.css` to match your brand colors and preferences.

## 📱 Responsive Design

The interface automatically adapts to different screen sizes:
- Desktop: Full-featured experience with spacious layout
- Tablet: Optimized spacing and controls
- Mobile: Compact design that maximizes screen real estate

## 🔄 API Integration

This chat UI works with any backend that supports:
- Server-Sent Events (SSE) for streaming responses
- JSON format for message exchange
- Token-by-token streaming capability

## 📦 Project Structure

```
/
├── src/
│   ├── App.jsx          # Main application component
│   ├── App.css          # Styling with CSS variables
│   └── main.jsx         # Entry point
├── public/              # Static assets
└── index.html           # HTML template
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🙏 Acknowledgments

- Inspired by modern chat interfaces like ChatGPT and Lobe Chat
- Built with React for a responsive and interactive experience
