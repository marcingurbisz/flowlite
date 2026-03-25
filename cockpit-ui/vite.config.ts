import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import istanbul from 'vite-plugin-istanbul';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    istanbul({
      include: 'src/**/*',
      requireEnv: true,
      forceBuildInstrument: true,
    }),
  ],
  build: {
    sourcemap: true,
  },
});
