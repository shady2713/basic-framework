import type { ApiSelectProps } from '#/components/form-create/typing';

import { defineComponent, onMounted, ref, useAttrs } from 'vue';

import { useUserStore } from '@vben/stores';
import { logWarn } from '@vben/utils';

import {
  ElCheckbox,
  ElCheckboxGroup,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
} from 'element-plus';

import { requestClient } from '#/api/request';

import {
  isSafeApiPath,
  isSafeParameterName,
  parseApiRequestData,
  parseApiSelectOptions,
} from './api-select-options';

export function useApiSelect(option: ApiSelectProps) {
  return defineComponent({
    name: option.name,
    props: {
      // 选项标签
      labelField: {
        type: String,
        default: () => option.labelField ?? 'label',
      },
      // 选项的值
      valueField: {
        type: String,
        default: () => option.valueField ?? 'value',
      },
      // api 接口
      url: {
        type: String,
        default: () => option.url ?? '',
      },
      // 请求类型
      method: {
        type: String,
        default: 'GET',
      },
      // 请求参数
      data: {
        type: String,
        default: '',
      },
      // 选择器类型，下拉框 select、多选框 checkbox、单选框 radio
      selectType: {
        type: String,
        default: 'select',
      },
      // 是否多选
      multiple: {
        type: Boolean,
        default: false,
      },
      // 是否远程搜索
      remote: {
        type: Boolean,
        default: false,
      },
      // 远程搜索时携带的参数
      remoteField: {
        type: String,
        default: 'label',
      },
      // 返回值类型（用于部门选择器等）：id 返回 ID，name 返回名称
      returnType: {
        type: String,
        default: 'id',
      },
      // 是否默认选中当前用户（仅用于 UserSelect）
      defaultCurrentUser: {
        type: Boolean,
        default: false,
      },
    },
    setup(props, { emit }) {
      const attrs = useAttrs();
      const options = ref<any[]>([]); // 下拉数据
      const loading = ref(false); // 是否正在从远程获取数据
      const queryParam = ref<any>(); // 当前输入的值
      const warn = (message: string) => {
        if (!import.meta.env.DEV) {
          return;
        }
        logWarn('api-select', message);
      };

      // 检查是否有有效的预设值
      function hasValidPresetValue(): boolean {
        const value = attrs.modelValue;
        if (value === undefined || value === null || value === '') {
          return false;
        }
        if (Array.isArray(value)) {
          return value.length > 0;
        }
        return true;
      }

      // 设置默认当前用户
      function setDefaultCurrentUser(): void {
        if (option.name !== 'UserSelect' || !props.defaultCurrentUser) {
          return;
        }
        if (hasValidPresetValue()) {
          return;
        }
        const userStore = useUserStore();
        const currentUserId = userStore.userInfo?.id;
        if (currentUserId) {
          const defaultValue = props.multiple ? [currentUserId] : currentUserId;
          emit('update:modelValue', defaultValue);
        }
      }

      function parseOptions(data: unknown) {
        const parsedOptions = parseApiSelectOptions(data, props);
        if (parsedOptions === null) {
          warn(`接口[${props.url}] 返回结果必须是数组或包含 list 数组`);
          return;
        }
        options.value = parsedOptions;
      }

      const getOptions = async () => {
        options.value = [];
        // 接口选择器
        if (!props.url) {
          return;
        }
        if (!isSafeApiPath(props.url)) {
          warn(`接口地址[${props.url}]不是站内绝对路径，已拒绝请求`);
          return;
        }

        switch (props.method) {
          case 'GET': {
            if (props.remote && !isSafeParameterName(props.remoteField)) {
              warn(`远程搜索参数名[${props.remoteField}]无效，已拒绝请求`);
              return;
            }
            parseOptions(
              await requestClient.get(props.url, {
                params:
                  props.remote && queryParam.value !== undefined
                    ? { [props.remoteField]: queryParam.value }
                    : undefined,
              }),
            );
            break;
          }
          case 'POST': {
            if (props.remote && !isSafeParameterName(props.remoteField)) {
              warn(`远程搜索参数名[${props.remoteField}]无效，已拒绝请求`);
              return;
            }
            try {
              const requestData = parseApiRequestData(props.data);
              const data =
                props.remote && queryParam.value !== undefined
                  ? { ...requestData, [props.remoteField]: queryParam.value }
                  : requestData;
              parseOptions(await requestClient.post(props.url, data));
            } catch (error) {
              warn(
                `接口[${props.url}]请求参数不是有效的 JSON 对象：${error instanceof Error ? error.message : '未知错误'}`,
              );
            }
            break;
          }
          default: {
            warn(`接口[${props.url}]请求方法[${props.method}]不受支持`);
          }
        }
      };

      const remoteMethod = async (query: any) => {
        if (!query) {
          return;
        }
        loading.value = true;
        try {
          queryParam.value = query;
          await getOptions();
        } finally {
          loading.value = false;
        }
      };

      onMounted(async () => {
        await getOptions();
        // 设置默认当前用户（仅用于 UserSelect）
        setDefaultCurrentUser();
      });

      const buildSelect = () => {
        if (props.multiple) {
          return (
            <ElSelect
              class="w-1/1"
              loading={loading.value}
              multiple
              {...attrs}
              filterable={props.remote}
              remote={props.remote}
              {...(props.remote && { remoteMethod })}
            >
              {options.value.map(
                (item: { label: any; value: any }, index: any) => (
                  <ElOption key={index} label={item.label} value={item.value} />
                ),
              )}
            </ElSelect>
          );
        }
        return (
          <ElSelect
            class="w-1/1"
            loading={loading.value}
            {...attrs}
            filterable={props.remote}
            remote={props.remote}
            {...(props.remote && { remoteMethod })}
          >
            {options.value.map(
              (item: { label: any; value: any }, index: any) => (
                <ElOption key={index} label={item.label} value={item.value} />
              ),
            )}
          </ElSelect>
        );
      };
      const buildCheckbox = () => {
        if (options.value.length === 0) {
          options.value = [
            { label: '选项1', value: '选项1' },
            { label: '选项2', value: '选项2' },
          ];
        }
        return (
          <ElCheckboxGroup class="w-1/1" {...attrs}>
            {options.value.map(
              (item: { label: any; value: any }, index: any) => (
                <ElCheckbox key={index} label={item.value}>
                  {item.label}
                </ElCheckbox>
              ),
            )}
          </ElCheckboxGroup>
        );
      };
      const buildRadio = () => {
        if (options.value.length === 0) {
          options.value = [
            { label: '选项1', value: '选项1' },
            { label: '选项2', value: '选项2' },
          ];
        }
        return (
          <ElRadioGroup class="w-1/1" {...attrs}>
            {options.value.map(
              (item: { label: any; value: any }, index: any) => (
                <ElRadio key={index} label={item.value}>
                  {item.label}
                </ElRadio>
              ),
            )}
          </ElRadioGroup>
        );
      };
      return () => (
        <>
          {(() => {
            switch (props.selectType) {
              case 'checkbox': {
                return buildCheckbox();
              }
              case 'radio': {
                return buildRadio();
              }
              case 'select': {
                return buildSelect();
              }
              default: {
                return buildSelect();
              }
            }
          })()}
        </>
      );
    },
  });
}
