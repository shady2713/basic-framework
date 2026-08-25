import Vue from '@vitejs/plugin-vue';
import VueJsx from '@vitejs/plugin-vue-jsx';
import { configDefaults, defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [Vue(), VueJsx()],
  test: {
    environment: 'happy-dom',
    exclude: [
      ...configDefaults.exclude,
      '**/e2e/**',
      '**/dist/**',
      '**/.{idea,git,cache,output,temp}/**',
      '**/node_modules/**',
      '**/{stylelint,eslint}.config.*',
      '.prettierrc.mjs',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: [
        'apps/web-ele/src/**/*.{ts,vue}',
        'packages/**/src/**/*.{ts,vue}',
      ],
      exclude: [
        '**/__tests__/**',
        '**/*.{spec,test}.[tj]s?(x)',
        '**/*.d.ts',
        '**/generated/**',
        '**/mock*/**',
        // entry assembly files: wiring only, no testable logic
        'apps/web-ele/src/main.ts',
        'apps/web-ele/src/bootstrap.ts',
      ],
      thresholds: {
        // Ratchet baseline measured on 2026-08-25 (396 tests, all green):
        //   lines 21.14% / statements 21.14% / functions 32.44% / branches 57.08%
        // Ratchet start: only allowed to go UP, never down.
        // When raising coverage, update these values in the same change.
        branches: 57,
        functions: 32,
        lines: 21.1,
        statements: 21.1,
      },
    },
  },
});
