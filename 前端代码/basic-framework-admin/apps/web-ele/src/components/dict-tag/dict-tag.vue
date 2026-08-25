<script setup lang="ts">
import { computed } from 'vue';

import { getDictObj } from '@vben/hooks';

import { ElTag } from 'element-plus';

interface DictTagProps {
  type: string; // 字典类型
  value: boolean | number | string; // 字典值
  icon?: string; // 图标
}

const props = defineProps<DictTagProps>();

/** 获取字典标签 */
const dictTag = computed(() => {
  const defaultDict = {
    label: '',
    colorType: 'primary',
  };
  // 校验参数有效性
  if (!props.type || props.value === undefined || props.value === null) {
    return defaultDict;
  }

  // 获取字典对象
  const dict = getDictObj(props.type, String(props.value));
  if (!dict) {
    return defaultDict;
  }

  // 处理颜色类型
  let colorType = dict.colorType;
  switch (colorType) {
    case 'danger': {
      colorType = 'danger';
      break;
    }
    case 'info': {
      colorType = 'info';
      break;
    }
    case 'primary': {
      colorType = 'primary';
      break;
    }
    case 'success': {
      colorType = 'success';
      break;
    }
    case 'warning': {
      colorType = 'warning';
      break;
    }
    default: {
      if (!colorType) {
        colorType = 'primary';
      }
    }
  }

  return {
    label: dict.label || '',
    colorType,
  };
});
</script>

<template>
  <ElTag v-if="dictTag.label" :type="dictTag.colorType as any">
    {{ dictTag.label }}
  </ElTag>
</template>
