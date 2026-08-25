<script lang="ts" setup>
import type { CrmCustomerApi } from '#/api/crm/customer';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createCustomer,
  getCustomer,
  updateCustomer,
} from '#/api/crm/customer';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<CrmCustomerApi.Customer>();

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-2',
    labelWidth: 100,
  },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    // 提交表单
    const data = (await formApi.getValues()) as CrmCustomerApi.Customer;
    try {
      await (formData.value?.id ? updateCustomer(data) : createCustomer(data));
      // 关闭并提示
      await modalApi.close();
      emit('success');
      showSuccessMessage($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      return;
    }
    const data = modalApi.getData<CrmCustomerApi.Customer>();
    const isEdit = data?.id;
    modalApi.setState({
      title: isEdit
        ? $t('ui.actionTitle.edit', ['客户'])
        : $t('ui.actionTitle.create', ['客户']),
    });
    formData.value = data;
    if (data?.id) {
      const detail = await getCustomer(data.id);
      formApi.setValues(detail);
    } else {
      formApi.resetForm();
    }
  },
});
</script>

<template>
  <Modal>
    <Form />
  </Modal>
</template>
