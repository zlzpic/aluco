module.exports = {
  root: true,
  env: { browser: true, es2020: true },
  parser: '@typescript-eslint/parser',
  extends: ['eslint:recommended'],
  ignorePatterns: ['dist', 'node_modules'],
}
