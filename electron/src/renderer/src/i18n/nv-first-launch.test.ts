import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => vi.restoreAllMocks());

it('defaults to Portuguese on first launch on an English system', async () => {
  localStorage.removeItem('voicestudio.locale');
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['en-US']);
  const { default: i18n } = await import('./index');
  expect(i18n.language).toBe('pt');
  expect(document.documentElement.lang).toBe('pt');
});
