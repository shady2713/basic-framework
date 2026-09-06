import type {
  AxiosRequestConfig,
  ErrorMode,
  PageParam,
  PageResult,
} from '@vben/request';

import { requestClient } from '#/api/request';

/** Axios 上传进度事件 */
export type AxiosProgressEvent = AxiosRequestConfig['onUploadProgress'];

export namespace InfraFileApi {
  /** 文件信息 */
  export interface File {
    id?: number;
    configId?: number;
    path: string;
    name?: string;
    url?: string;
    size?: number;
    type?: string;
    accessType?: number;
    createTime?: Date;
  }

  /** 文件预签名地址 */
  export interface FilePresignedUrlRespVO {
    uploadToken: string; // 一次性上传完成凭据
    uploadUrl: string; // 文件上传 URL
  }

  export interface FileCreateReqVO {
    uploadToken: string;
  }

  /** 上传文件 */
  export interface FileUploadReqVO {
    file: globalThis.File;
    directory?: string;
    publicRead?: boolean;
  }
}

/** 查询文件列表 */
export function getFilePage(params: PageParam) {
  return requestClient.get<PageResult<InfraFileApi.File>>('/infra/file/page', {
    params,
  });
}

/** 删除文件 */
export function deleteFile(id: number) {
  return requestClient.delete(`/infra/file/delete?id=${id}`);
}

/** 批量删除文件 */
export function deleteFileList(ids: number[]) {
  return requestClient.delete(`/infra/file/delete-list?ids=${ids.join(',')}`);
}

/** 获取文件预签名地址 */
export function getFilePresignedUrl(
  name: string,
  size: number,
  type?: string,
  directory?: string,
  publicRead = false,
) {
  return requestClient.get<InfraFileApi.FilePresignedUrlRespVO>(
    '/infra/file/presigned-url',
    {
      params: {
        name,
        size,
        type,
        directory,
        ...(publicRead ? { publicRead } : {}),
      },
    },
  );
}

/** 创建文件 */
export function createFile(data: InfraFileApi.FileCreateReqVO) {
  return requestClient.post<string>('/infra/file/create', data);
}

/** 上传文件 */
export function uploadFile(
  data: InfraFileApi.FileUploadReqVO,
  onUploadProgress?: AxiosProgressEvent,
  errorMode: ErrorMode = 'global',
) {
  // upload 会把显式的 undefined 编码进 multipart；构造新对象以保持调用方参数不可变。
  const payload: InfraFileApi.FileUploadReqVO = {
    file: data.file,
    ...(data.directory ? { directory: data.directory } : {}),
    ...(data.publicRead ? { publicRead: true } : {}),
  };
  return requestClient.upload<string, InfraFileApi.FileUploadReqVO>(
    '/infra/file/upload',
    payload,
    {
      errorMode,
      onUploadProgress,
    },
  );
}
