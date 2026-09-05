import { describe, expect, it, vi } from 'vitest';

import { resolveScrollPosition } from './index';

vi.mock('@vben/utils', () => ({ resetStaticRoutes: vi.fn() }));
vi.mock('./guard', () => ({ createRouterGuard: vi.fn() }));
vi.mock('./routes', () => ({ routes: [] }));

describe('router scroll behavior', () => {
  it('restores browser history positions before applying route defaults', () => {
    const savedPosition = { left: 12, top: 34 };

    expect(resolveScrollPosition('#details', savedPosition)).toBe(
      savedPosition,
    );
  });

  it('smoothly targets anchors and otherwise returns to the page origin', () => {
    expect(resolveScrollPosition('#details', null)).toEqual({
      behavior: 'smooth',
      el: '#details',
    });
    expect(resolveScrollPosition('', null)).toEqual({ left: 0, top: 0 });
  });
});
