import type {
  UploadFile,
  UploadProgressEvent,
  UploadRequestOptions,
} from 'element-plus';

import type { FileUploadProps, UploadApiResult } from './typing';

import type { AxiosProgressEvent } from '#/api/infra/file';

import { isFunction, isObject, isString } from '@vben/utils';

import { useUpload } from './use-upload';

export function unwrapUploadResponse(response: unknown): unknown {
  if (isObject(response) && 'data' in (response as Record<string, unknown>)) {
    return (response as Record<string, unknown>).data;
  }
  return response;
}

export function resolveUploadUrl(response: unknown): string {
  const value = unwrapUploadResponse(response);
  if (isString(value)) {
    return value;
  }
  if (isObject(value)) {
    const data = (value as Record<string, unknown>).data;
    const url = (value as Record<string, unknown>).url;
    if (isString(url)) {
      return url;
    }
    if (isString(data)) {
      return data;
    }
  }
  return '';
}

export function resolveUploadValue(
  file: UploadFile,
  resultField?: string,
): unknown {
  const response = unwrapUploadResponse(file.response);
  if (resultField && response !== null && response !== undefined) {
    return response;
  }
  return file.url || resolveUploadUrl(response) || response;
}

export async function requestUpload(
  props: Pick<FileUploadProps, 'api' | 'directory'>,
  options: UploadRequestOptions,
): Promise<UploadApiResult> {
  let { api } = props;
  if (!api || !isFunction(api)) {
    api = useUpload(props.directory, 'inline').httpRequest;
  }
  const progressEvent: AxiosProgressEvent = (event) => {
    const total = event.total || 0;
    const percent = total > 0 ? Math.trunc((event.loaded / total) * 100) : 0;
    options.onProgress?.({
      percent,
      total,
      loaded: event.loaded || 0,
      lengthComputable: true,
    } as unknown as UploadProgressEvent);
  };
  const result = await api?.(options.file, progressEvent);
  if (result === undefined) {
    throw new Error('上传接口未返回结果');
  }
  return result;
}
