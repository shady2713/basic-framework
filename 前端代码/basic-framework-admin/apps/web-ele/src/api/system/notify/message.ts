import type { PageParam } from '@vben/request';

import { requestClient } from '#/api/request';

/** 获得当前用户的站内信分页 */
export function getNotifyMessagePage(params: PageParam) {
  return requestClient.get('/system/notify-message/page', { params });
}

/** 获得当前用户的未读站内信数量 */
export function getUnreadNotifyMessageCount() {
  return requestClient.get('/system/notify-message/get-unread-count');
}

/** 获得当前用户的未读站内信列表 */
export function getUnreadNotifyMessageList() {
  return requestClient.get('/system/notify-message/get-unread-list');
}

/** 标记站内信为已读 */
export function updateNotifyMessageRead(id: number) {
  return requestClient.put(`/system/notify-message/update-read?id=${id}`);
}

/** 标记所有站内信为已读 */
export function updateAllNotifyMessageRead() {
  return requestClient.put('/system/notify-message/update-all-read');
}
