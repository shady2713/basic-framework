<script setup lang="ts">
import type { PropType } from 'vue';

import type { ActionItem, ButtonType, PopConfirm } from './typing';

import { computed, toRaw } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { isFunction } from '@vben/utils';

import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElPopconfirm,
  ElSpace,
  ElTooltip,
} from 'element-plus';

const props = defineProps({
  actions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  dropDownActions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  divider: {
    type: Boolean,
    default: true,
  },
});

const { hasAccessByCodes } = useAccess();

type ResolvedAction = ActionItem & {
  enable: boolean;
  label: string;
  onCancel?: () => void;
  onConfirm?: () => void;
  type: ButtonType;
};

type DropdownAction = Omit<ActionItem, 'text'> & {
  divider: boolean;
  label: string;
  onCancel?: () => void;
  onConfirm?: () => void;
  text: string;
};

/** 是否显示 */
function isIfShow(action: ActionItem): boolean {
  const ifShow = action.ifShow;
  let visible = true;
  if (typeof ifShow === 'boolean') {
    visible = ifShow;
  }
  if (isFunction(ifShow)) {
    visible = ifShow(action);
  }
  if (visible) {
    visible =
      hasAccessByCodes(action.auth || []) || (action.auth || []).length === 0;
  }
  return visible;
}

/** 处理按钮 actions */
const getActions = computed<ResolvedAction[]>(() => {
  return (toRaw(props.actions) || [])
    .filter((action) => {
      return (
        (hasAccessByCodes(action.auth || []) ||
          (action.auth || []).length === 0) &&
        isIfShow(action)
      );
    })
    .map((action) => {
      const { popConfirm } = action;
      return {
        ...action,
        label: action.label || '',
        type: (action.type || 'primary') as ButtonType,
        onConfirm: popConfirm?.confirm,
        onCancel: popConfirm?.cancel,
        enable: !!popConfirm,
      };
    });
});

const getDropdownList = computed<DropdownAction[]>(() => {
  return (toRaw(props.dropDownActions) || [])
    .filter((action) => {
      return (
        (hasAccessByCodes(action.auth || []) ||
          (action.auth || []).length === 0) &&
        isIfShow(action)
      );
    })
    .map((action, index) => {
      const { label, popConfirm } = action;
      return {
        ...action,
        onConfirm: popConfirm?.confirm,
        onCancel: popConfirm?.cancel,
        label: label || '',
        text: label || '',
        divider:
          index < props.dropDownActions.length - 1 ? props.divider : false,
      };
    });
});

function getPopConfirmProps(attrs?: PopConfirm) {
  if (!attrs) {
    return {};
  }
  const originAttrs = { ...attrs } as Record<string, unknown>;
  delete originAttrs.icon;
  if (attrs.confirm && isFunction(attrs.confirm)) {
    originAttrs.onConfirm = attrs.confirm;
    delete originAttrs.confirm;
  }
  if (attrs.cancel && isFunction(attrs.cancel)) {
    originAttrs.onCancel = attrs.cancel;
    delete originAttrs.cancel;
  }
  return originAttrs;
}

function getButtonProps(action: ResolvedAction) {
  const res = {
    ...action,
    label: action.label || '',
    type: (action.type || 'primary') as ButtonType,
  };
  delete res.icon;
  return res;
}

function handleMenuClick(command: number | string) {
  const action = getDropdownList.value[Number(command)];
  if (action?.onClick && isFunction(action.onClick)) {
    action.onClick();
  }
}
</script>

<template>
  <div class="table-actions">
    <ElSpace
      :size="
        getActions?.some((item: ActionItem) => item.type === 'text') ? 0 : 8
      "
    >
      <template v-for="(action, index) in getActions" :key="index">
        <ElPopconfirm
          v-if="action.popConfirm"
          v-bind="getPopConfirmProps(action.popConfirm)"
        >
          <template v-if="action.popConfirm.icon" #icon>
            <IconifyIcon :icon="action.popConfirm.icon" />
          </template>
          <template #reference>
            <ElTooltip
              v-if="
                action.tooltip &&
                ((typeof action.tooltip === 'string' && action.tooltip) ||
                  (typeof action.tooltip === 'object' &&
                    action.tooltip.content))
              "
              v-bind="
                typeof action.tooltip === 'string'
                  ? { content: action.tooltip }
                  : { ...action.tooltip }
              "
            >
              <ElButton v-bind="getButtonProps(action)">
                <template v-if="action.icon">
                  <IconifyIcon :icon="action.icon" class="mr-1" />
                </template>
                {{ action.label }}
              </ElButton>
            </ElTooltip>
            <ElButton v-else v-bind="getButtonProps(action)">
              <template v-if="action.icon">
                <IconifyIcon :icon="action.icon" class="mr-1" />
              </template>
              {{ action.label }}
            </ElButton>
          </template>
        </ElPopconfirm>
        <ElTooltip
          v-else-if="
            action.tooltip &&
            ((typeof action.tooltip === 'string' && action.tooltip) ||
              (typeof action.tooltip === 'object' && action.tooltip.content))
          "
          v-bind="
            typeof action.tooltip === 'string'
              ? { content: action.tooltip }
              : { ...action.tooltip }
          "
        >
          <ElButton v-bind="getButtonProps(action)" @click="action.onClick">
            <template v-if="action.icon">
              <IconifyIcon :icon="action.icon" class="mr-1" />
            </template>
            {{ action.label }}
          </ElButton>
        </ElTooltip>
        <ElButton
          v-else
          v-bind="getButtonProps(action)"
          @click="action.onClick"
        >
          <template v-if="action.icon">
            <IconifyIcon :icon="action.icon" class="mr-1" />
          </template>
          {{ action.label }}
        </ElButton>
      </template>
    </ElSpace>

    <ElDropdown v-if="getDropdownList.length > 0" @command="handleMenuClick">
      <slot name="more">
        <ElButton :type="getDropdownList[0]?.type" link>
          {{ $t('page.action.more') }}
          <IconifyIcon icon="lucide:ellipsis-vertical" class="ml-1" />
        </ElButton>
      </slot>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem
            v-for="(action, index) in getDropdownList"
            :key="index"
            :command="index"
            :disabled="action.disabled"
          >
            <template v-if="action.popConfirm">
              <ElPopconfirm v-bind="getPopConfirmProps(action.popConfirm)">
                <template v-if="action.popConfirm.icon" #icon>
                  <IconifyIcon :icon="action.popConfirm.icon" />
                </template>
                <template #reference>
                  <div>
                    <IconifyIcon v-if="action.icon" :icon="action.icon" />
                    <span :class="action.icon ? 'ml-1' : ''">
                      {{ action.text }}
                    </span>
                  </div>
                </template>
              </ElPopconfirm>
            </template>
            <template v-else>
              <div>
                <IconifyIcon v-if="action.icon" :icon="action.icon" />
                {{ action.label }}
              </div>
            </template>
          </ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
  </div>
</template>
<style lang="scss">
.table-actions {
  .el-button--text {
    padding: 4px;
    margin-left: 0;
  }

  .el-button .iconify + span,
  .el-button span + .iconify {
    margin-inline-start: 4px;
  }

  .iconify {
    display: inline-flex;
    align-items: center;
    width: 1em;
    height: 1em;
    font-style: normal;
    line-height: 0;
    vertical-align: -0.125em;
    color: inherit;
    text-align: center;
    text-transform: none;
    text-rendering: optimizelegibility;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }
}

.el-popconfirm {
  .el-popconfirm__action {
    .el-button {
      margin-left: 8px !important;
    }
  }
}
</style>
