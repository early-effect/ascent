import { defineConfig } from 'vite';
// Vendored, sbt-2.x-hardened fork (see example/_vendor) — upstream picks up sbt 2.x's trailing
// ANSI escape as the output path; our fork strips it.
import scalaJSPlugin from 'vite-plugin-scalajs-ascent';

export default defineConfig({
  plugins: [
    // `cwd` points at the sbt project root (two levels up); `projectID` matches this example's
    // projectmatrix build target (`sbt show hybridChat/projectID`).
    scalaJSPlugin({ cwd: '../..', projectID: 'hybridChatJS' }),
  ],
  server: {
    open: true,
    // Proxy the chat SSE stream + actions to the zio-http server (run via
    // `sbt hybridChatServer/run`). Keeps client + server same-origin in the browser.
    proxy: {
      '/chat/sse': { target: 'http://localhost:8080', changeOrigin: true },
      '/chat/send': { target: 'http://localhost:8080', changeOrigin: true },
      '/chat/typing': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
