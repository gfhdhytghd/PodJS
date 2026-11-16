import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';
export default tseslint.config(
  {ignores:['**/node_modules/**','vendor/**','**/build/**','dist/**','artifacts/**','.cache/**','.codex-tmp/**','**/__pycache__/**','platforms/harmony/entry/src/main/resources/rawfile/main.js']},
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {files:['**/*.{js,mjs,cjs,ts,tsx}'],languageOptions:{globals:{...globals.node,...globals.bun}}},
  // TypeScript name resolution is checked by tsc; JS keeps no-undef.
  {files:['**/*.{ts,tsx}'],rules:{'no-undef':'off'}},
);
