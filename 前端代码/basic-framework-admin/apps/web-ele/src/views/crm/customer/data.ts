import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { buildRequiredMobileSchema } from '#/adapter/field-rules';
import { z } from '#/adapter/form';

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'id',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      component: 'Input',
      fieldName: 'name',
      label: '客户名称',
      componentProps: {
        placeholder: '请输入客户名称',
      },
      rules: z
        .string()
        .trim()
        .min(1, '请输入客户名称')
        .max(100, '客户名称长度不能超过 100 个字符'),
    },
    {
      component: 'Input',
      fieldName: 'mobile',
      label: '手机号',
      componentProps: {
        placeholder: '请输入手机号',
      },
      rules: buildRequiredMobileSchema('手机号'),
    },
    {
      component: 'InputNumber',
      fieldName: 'amount',
      label: '合同金额（元）',
      componentProps: {
        min: 0,
        precision: 2,
        placeholder: '请输入合同金额',
        controlsPosition: 'right',
        class: '!w-full',
      },
      rules: z
        .number({ message: '请输入合同金额' })
        .min(0, '合同金额不能小于 0'),
    },
    {
      component: 'DatePicker',
      fieldName: 'contractDate',
      label: '合同日期',
      componentProps: {
        valueFormat: 'YYYY-MM-DD',
        placeholder: '请选择合同日期',
      },
      rules: z.string({ message: '请选择合同日期' }).min(1, '请选择合同日期'),
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '客户名称',
      component: 'Input',
      componentProps: {
        placeholder: '请输入客户名称',
        clearable: true,
      },
    },
    {
      fieldName: 'mobile',
      label: '手机号',
      component: 'Input',
      componentProps: {
        placeholder: '请输入手机号',
        clearable: true,
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '客户编号',
      minWidth: 100,
    },
    {
      field: 'name',
      title: '客户名称',
      minWidth: 180,
    },
    {
      field: 'mobile',
      title: '手机号',
      minWidth: 140,
    },
    {
      field: 'amount',
      title: '合同金额（元）',
      minWidth: 130,
    },
    {
      field: 'contractDate',
      title: '合同日期',
      minWidth: 120,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 130,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
