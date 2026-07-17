import { defineConfig } from "vite";
import path from "node:path";

const rawPort = process.env.PORT ?? "24402";
const port = Number(rawPort);
if (Number.isNaN(port) || port <= 0) throw new Error(`Invalid PORT: "${rawPort}"`);

const basePath = process.env.BASE_PATH ?? "/";
const scalaPort = process.env.SCALA_PORT ?? "9000";

export default defineConfig(({ command }) => ({
  base: basePath,
  publicDir: path.resolve(
    import.meta.dirname,
    `../../game/client/target/scala-3.3.4/client-${command === "build" ? "opt" : "fastopt"}`
  ),
  appType: "mpa",
  server: {
    port,
    strictPort: true,
    host: "0.0.0.0",
    allowedHosts: true,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/ws": {
        target: `ws://localhost:${scalaPort}`,
        ws: true,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port,
    host: "0.0.0.0",
    allowedHosts: true,
  },
  build: {
    outDir: path.resolve(import.meta.dirname, "dist/public"),
    emptyOutDir: true,
    rollupOptions: {
      input: path.resolve(import.meta.dirname, "index.html"),
    },
  },
}));
