<script lang="ts" setup>
/** 代码生成管理页面 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { InfraCodegenApi } from '#/api/infra/codegen';

import { useRouter } from 'vue-router';

import { Page, useVbenModal } from '@vben/common-ui';
import { isEmpty } from '@vben/utils';

import { ElLoading } from 'element-plus';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCodegenTable,
  deleteCodegenTableList,
  downloadCodegen,
  getCodegenTablePage,
  syncCodegenFromDB,
} from '#/api/infra/codegen';
import { useCrudDeleteActions } from '#/composables/use-crud-actions';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useGridColumns, useGridFormSchema } from './data';
import ImportTable from './modules/import-table.vue';
import PreviewCode from './modules/preview-code.vue';

const router = useRouter();
const [ImportModal, importModalApi] = useVbenModal({
  connectedComponent: ImportTable,
  destroyOnClose: true,
});

const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: PreviewCode,
  destroyOnClose: true,
});

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 导入表格 */
function handleImport() {
  importModalApi.open();
}

/** 预览代码 */
function handlePreview(row: InfraCodegenApi.CodegenTable) {
  previewModalApi.setData(row).open();
}

/** 编辑表格 */
function handleEdit(row: InfraCodegenApi.CodegenTable) {
  router.push({ name: 'InfraCodegenEdit', query: { id: row.id } });
}

const { checkedIds, handleDelete, handleDeleteBatch, handleRowCheckboxChange } =
  useCrudDeleteActions<InfraCodegenApi.CodegenTable>({
    batchDeleteApi: deleteCodegenTableList,
    deleteApi: deleteCodegenTable,
    getDeleteName: (row) => row.tableName || '',
    getRowKey: (row) => row.id,
    refresh: handleRefresh,
  });

/** 同步数据库 */
async function handleSync(row: InfraCodegenApi.CodegenTable) {
  const loadingInstance = ElLoading.service({
    text: $t('ui.actionMessage.updating', [row.tableName]),
  });
  try {
    await syncCodegenFromDB(row.id);
    showSuccessMessage($t('ui.actionMessage.updateSuccess', [row.tableName]));
    handleRefresh();
  } finally {
    loadingInstance.close();
  }
}

/** 生成代码 */
async function handleGenerate(row: InfraCodegenApi.CodegenTable) {
  const loadingInstance = ElLoading.service({
    text: '正在生成代码...',
  });
  try {
    const res = await downloadCodegen(row.id);
    const blob = new Blob([res], { type: 'application/zip' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `codegen-${row.className}.zip`;
    link.click();
    window.URL.revokeObjectURL(url);
    showSuccessMessage('代码生成成功');
  } finally {
    loadingInstance.close();
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getCodegenTablePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<InfraCodegenApi.CodegenTable>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});
</script>
<template>
  <Page auto-content-height>
    <ImportModal @success="handleRefresh" />
    <PreviewModal />
    <Grid table-title="代码生成列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.import'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['infra:codegen:create'],
              onClick: handleImport,
            },
            {
              label: $t('ui.actionTitle.deleteBatch'),
              type: 'danger',
              icon: ACTION_ICON.DELETE,
              disabled: isEmpty(checkedIds),
              auth: ['infra:codegen:delete'],
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '预览',
              type: 'primary',
              link: true,
              icon: ACTION_ICON.VIEW,
              auth: ['infra:codegen:preview'],
              onClick: handlePreview.bind(null, row),
            },
            {
              label: '生成代码',
              type: 'primary',
              link: true,
              icon: ACTION_ICON.DOWNLOAD,
              auth: ['infra:codegen:download'],
              onClick: handleGenerate.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              auth: ['infra:codegen:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '同步',
              type: 'primary',
              link: true,
              auth: ['infra:codegen:update'],
              onClick: handleSync.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              auth: ['infra:codegen:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.tableName]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
