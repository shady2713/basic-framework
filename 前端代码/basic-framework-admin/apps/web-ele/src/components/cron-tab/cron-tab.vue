<script lang="ts" setup>
import type { PropType } from 'vue';

import type { CronValue, ShortcutsType } from './types';

import { computed, onMounted, reactive, ref, watch } from 'vue';

import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTabPane,
  ElTabs,
} from 'element-plus';

import { showWarningMessage } from '#/utils/feedback';

import {
  formatCronExpression,
  formatCronField,
  parseCronExpression,
} from './cron-utils';
import { createDefaultCronValue, CronDataDefault } from './types';

defineOptions({ name: 'Crontab' });

const props = defineProps({
  modelValue: {
    type: String,
    default: '* * * * * ?',
  },
  shortcuts: {
    type: Array as PropType<ShortcutsType[]>,
    default: () => [],
  },
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const defaultValue = ref('');
const dialogVisible = ref(false);

const cronValue = reactive<CronValue>(createDefaultCronValue());

const data = CronDataDefault;
const value_second = computed(() =>
  formatCronField('second', cronValue.second),
);
const value_minute = computed(() =>
  formatCronField('minute', cronValue.minute),
);
const value_hour = computed(() => formatCronField('hour', cronValue.hour));
const value_day = computed(() => formatCronField('day', cronValue.day));
const value_month = computed(() => formatCronField('month', cronValue.month));
const value_week = computed(() => formatCronField('week', cronValue.week));
const value_year = computed(() => formatCronField('year', cronValue.year));

watch(
  () => cronValue.week.type,
  (val: string) => {
    if (val !== '5') {
      cronValue.day.type = '5';
    }
  },
);

watch(
  () => cronValue.day.type,
  (val: string) => {
    if (val !== '5') {
      cronValue.week.type = '5';
    }
  },
);

watch(
  () => props.modelValue,
  () => {
    defaultValue.value = props.modelValue;
  },
);

onMounted(() => {
  defaultValue.value = props.modelValue;
});

const select = ref<string>();

watch(
  () => select.value,
  (value) => {
    if (!value) {
      return;
    }
    if (value === 'custom') {
      open();
      select.value = undefined;
    } else {
      defaultValue.value = value;
      emit('update:modelValue', defaultValue.value);
    }
  },
);

function open() {
  set();
  dialogVisible.value = true;
}

function set() {
  defaultValue.value = props.modelValue;
  const parsed = parseCronExpression(props.modelValue || '* * * * * ?');
  if (!parsed) {
    showWarningMessage('Cron 表达式错误，已转换为默认表达式');
    Object.assign(cronValue, createDefaultCronValue());
    return;
  }
  Object.assign(cronValue, parsed);
}

function submit() {
  const expression = formatCronExpression(cronValue);
  if (!parseCronExpression(expression)) {
    showWarningMessage('Cron 表达式不完整，请检查后重试');
    return;
  }
  defaultValue.value = expression;
  emit('update:modelValue', defaultValue.value);
  dialogVisible.value = false;
}

function inputChange() {
  emit('update:modelValue', defaultValue.value);
}
</script>
<template>
  <ElInput
    v-model="defaultValue"
    class="input-with-select"
    v-bind="$attrs"
    @input="inputChange"
  >
    <template #append>
      <ElSelect v-model="select" placeholder="生成器" style="width: 115px">
        <ElOption label="每分钟" value="0 * * * * ?" />
        <ElOption label="每小时" value="0 0 * * * ?" />
        <ElOption label="每天零点" value="0 0 0 * * ?" />
        <ElOption label="每月一号零点" value="0 0 0 1 * ?" />
        <ElOption label="每月最后一天零点" value="0 0 0 L * ?" />
        <ElOption label="每周星期日零点" value="0 0 0 ? * 1" />
        <ElOption
          v-for="(item, index) in shortcuts"
          :key="`${item.value}-${index}`"
          :label="item.text"
          :value="item.value"
        />
        <ElOption label="自定义" value="custom" />
      </ElSelect>
    </template>
  </ElInput>

  <ElDialog
    v-model="dialogVisible"
    :width="580"
    append-to-body
    destroy-on-close
    title="Cron 规则生成器"
  >
    <div class="sc-cron">
      <ElTabs>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>秒</h2>
              <h4>{{ value_second }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.second.type">
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.second.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.second.range.start"
                :max="59"
                :min="0"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.second.range.end"
                :max="59"
                :min="0"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.second.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.second.loop.start"
                :max="59"
                :min="0"
                controls-position="right"
              />
              秒开始，每
              <ElInputNumber
                v-model="cronValue.second.loop.end"
                :max="59"
                :min="0"
                controls-position="right"
              />
              秒执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.second.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.second.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.second"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>分钟</h2>
              <h4>{{ value_minute }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.minute.type">
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.minute.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.minute.range.start"
                :max="59"
                :min="0"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.minute.range.end"
                :max="59"
                :min="0"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.minute.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.minute.loop.start"
                :max="59"
                :min="0"
                controls-position="right"
              />
              分钟开始，每
              <ElInputNumber
                v-model="cronValue.minute.loop.end"
                :max="59"
                :min="0"
                controls-position="right"
              />
              分钟执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.minute.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.minute.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.minute"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>小时</h2>
              <h4>{{ value_hour }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.hour.type">
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.hour.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.hour.range.start"
                :max="23"
                :min="0"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.hour.range.end"
                :max="23"
                :min="0"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.hour.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.hour.loop.start"
                :max="23"
                :min="0"
                controls-position="right"
              />
              小时开始，每
              <ElInputNumber
                v-model="cronValue.hour.loop.end"
                :max="23"
                :min="0"
                controls-position="right"
              />
              小时执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.hour.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.hour.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.hour"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>日</h2>
              <h4>{{ value_day }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.day.type">
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
                <ElRadioButton value="4">本月最后一天</ElRadioButton>
                <ElRadioButton value="5">不指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.day.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.day.range.start"
                :max="31"
                :min="1"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.day.range.end"
                :max="31"
                :min="1"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.day.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.day.loop.start"
                :max="31"
                :min="1"
                controls-position="right"
              />
              号开始，每
              <ElInputNumber
                v-model="cronValue.day.loop.end"
                :max="31"
                :min="1"
                controls-position="right"
              />
              天执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.day.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.day.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.day"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>月</h2>
              <h4>{{ value_month }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.month.type">
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.month.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.month.range.start"
                :max="12"
                :min="1"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.month.range.end"
                :max="12"
                :min="1"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.month.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.month.loop.start"
                :max="12"
                :min="1"
                controls-position="right"
              />
              月开始，每
              <ElInputNumber
                v-model="cronValue.month.loop.end"
                :max="12"
                :min="1"
                controls-position="right"
              />
              月执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.month.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.month.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.month"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>周</h2>
              <h4>{{ value_week }}</h4>
            </div>
          </template>
          <ElForm>
            <ElForm>
              <ElFormItem label="类型">
                <ElRadioGroup v-model="cronValue.week.type">
                  <ElRadioButton value="0">任意值</ElRadioButton>
                  <ElRadioButton value="1">范围</ElRadioButton>
                  <ElRadioButton value="2">间隔</ElRadioButton>
                  <ElRadioButton value="3">指定</ElRadioButton>
                  <ElRadioButton value="4">本月最后一周</ElRadioButton>
                  <ElRadioButton value="5">不指定</ElRadioButton>
                </ElRadioGroup>
              </ElFormItem>
              <ElFormItem v-if="cronValue.week.type === '1'" label="范围">
                <ElSelect v-model="cronValue.week.range.start">
                  <ElOption
                    v-for="(item, index) in data.week"
                    :key="index"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
                <span style="padding: 0 15px">-</span>
                <ElSelect v-model="cronValue.week.range.end">
                  <ElOption
                    v-for="(item, index) in data.week"
                    :key="index"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem v-if="cronValue.week.type === '2'" label="间隔">
                第
                <ElInputNumber
                  v-model="cronValue.week.loop.start"
                  :max="4"
                  :min="1"
                  controls-position="right"
                />
                周的星期
                <ElSelect v-model="cronValue.week.loop.end">
                  <ElOption
                    v-for="(item, index) in data.week"
                    :key="index"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
                执行一次
              </ElFormItem>
              <ElFormItem v-if="cronValue.week.type === '3'" label="指定">
                <ElSelect
                  v-model="cronValue.week.appoint"
                  multiple
                  style="width: 100%"
                >
                  <ElOption
                    v-for="(item, index) in data.week"
                    :key="index"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem v-if="cronValue.week.type === '4'" label="最后一周">
                <ElSelect v-model="cronValue.week.last">
                  <ElOption
                    v-for="(item, index) in data.week"
                    :key="index"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
            </ElForm>
          </ElForm>
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <div class="sc-cron-num">
              <h2>年</h2>
              <h4>{{ value_year }}</h4>
            </div>
          </template>
          <ElForm>
            <ElFormItem label="类型">
              <ElRadioGroup v-model="cronValue.year.type">
                <ElRadioButton value="-1">忽略</ElRadioButton>
                <ElRadioButton value="0">任意值</ElRadioButton>
                <ElRadioButton value="1">范围</ElRadioButton>
                <ElRadioButton value="2">间隔</ElRadioButton>
                <ElRadioButton value="3">指定</ElRadioButton>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="cronValue.year.type === '1'" label="范围">
              <ElInputNumber
                v-model="cronValue.year.range.start"
                controls-position="right"
              />
              <span style="padding: 0 15px">-</span>
              <ElInputNumber
                v-model="cronValue.year.range.end"
                controls-position="right"
              />
            </ElFormItem>
            <ElFormItem v-if="cronValue.year.type === '2'" label="间隔">
              <ElInputNumber
                v-model="cronValue.year.loop.start"
                controls-position="right"
              />
              年开始，每
              <ElInputNumber
                v-model="cronValue.year.loop.end"
                :min="1"
                controls-position="right"
              />
              年执行一次
            </ElFormItem>
            <ElFormItem v-if="cronValue.year.type === '3'" label="指定">
              <ElSelect
                v-model="cronValue.year.appoint"
                multiple
                style="width: 100%"
              >
                <ElOption
                  v-for="(item, index) in data.year"
                  :key="index"
                  :label="item"
                  :value="item"
                />
              </ElSelect>
            </ElFormItem>
          </ElForm>
        </ElTabPane>
      </ElTabs>
    </div>

    <template #footer>
      <ElButton @click="dialogVisible = false">取 消</ElButton>
      <ElButton type="primary" @click="submit()">确 认</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.sc-cron:deep(.el-tabs__item) {
  height: auto;
  padding: 0 7px;
  line-height: 1;
  vertical-align: bottom;
}

.sc-cron-num {
  width: 100%;
  margin-bottom: 15px;
  text-align: center;
}

.sc-cron-num h2 {
  margin-bottom: 15px;
  font-size: 12px;
  font-weight: normal;
}

.sc-cron-num h4 {
  display: block;
  width: 100%;
  height: 32px;
  padding: 0 15px;
  font-size: 12px;
  line-height: 30px;
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
}

.sc-cron:deep(.el-tabs__item.is-active) .sc-cron-num h4 {
  color: #fff;
  background: var(--el-color-primary);
}

[data-theme='dark'] .sc-cron-num h4 {
  background: var(--el-color-white);
}

.input-with-select .el-input-group__prepend {
  background-color: var(--el-fill-color-blank);
}
</style>
