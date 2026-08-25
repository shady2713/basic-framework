import type { Router } from 'vue-router';

declare global {
  interface Window {
    _hmt: any[];
  }
}

const HM_ID = import.meta.env.VITE_APP_BAIDU_CODE;

/**
 * 设置百度统计。
 *
 * 只上报 path，避免 query/hash 中的 token、手机号、业务编号等敏感参数发送到第三方。
 *
 * @param router Vue Router 实例
 */
function setupBaiduTongJi(router: Router) {
  if (!HM_ID) {
    return;
  }

  window._hmt = window._hmt || [];

  router.afterEach((to) => {
    window._hmt.push(['_trackPageview', to.path]);
  });
}

export { setupBaiduTongJi };
