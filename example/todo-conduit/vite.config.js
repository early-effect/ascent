import { defineConfig } from 'vite';
// Vendored, sbt-2.x-hardened fork (see example/_vendor) — upstream picks up sbt 2.x's trailing
// ANSI escape as the output path; our fork strips it.
import scalaJSPlugin from 'vite-plugin-scalajs-ascent';

export default defineConfig({
  plugins: [
    // `cwd` points at the sbt project root (two levels up from this example dir); `projectID`
    // matches this example's projectmatrix build target (see `sbt show todoConduitJS/projectID`).
    scalaJSPlugin({ cwd: '../..', projectID: 'todoConduitJS' }),
  ],
  server: { open: true },
});
