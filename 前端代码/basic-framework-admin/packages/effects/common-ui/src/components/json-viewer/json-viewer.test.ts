import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import JsonViewer from './index.vue';

const jsonParse = vi.hoisted(() => vi.fn());

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('json-bigint', () => ({
  default: () => ({ parse: jsonParse }),
}));

vi.mock('vue-json-viewer', () => ({
  default: defineComponent({
    name: 'VueJsonViewerStub',
    inheritAttrs: false,
    setup(_, { attrs }) {
      const listener = (name: string) =>
        typeof attrs[name] === 'function' ? attrs[name] : undefined;
      return () =>
        h(
          'div',
          {
            class: 'viewer-stub',
            'data-value': JSON.stringify(attrs.value),
          },
          [
            h(
              'div',
              { class: 'jv-push', depth: '2', path: 'order.id' },
              h(
                'span',
                { class: 'jv-item', onClick: listener('onClick') },
                '123',
              ),
            ),
            h(
              'span',
              {
                class: 'jv-item jv-item-without-path',
                onClick: listener('onClick'),
              },
              '456',
            ),
            h(
              'button',
              {
                class: 'emit-copy',
                onClick: () =>
                  listener('onCopied')?.({
                    action: 'copy',
                    text: '123',
                    trigger: document.createElement('button'),
                  }),
              },
              'copy',
            ),
            h(
              'button',
              {
                class: 'emit-key',
                onClick: () => listener('onKeyclick')?.('orderId'),
              },
              'key',
            ),
          ],
        );
    },
  }),
}));

describe('json viewer', () => {
  beforeEach(() => {
    jsonParse.mockReset();
    jsonParse.mockImplementation((value: string) => JSON.parse(value));
  });

  it('parses string input through the bigint-safe parser', () => {
    const wrapper = mount(JsonViewer, {
      props: { value: '{"orderId":"9007199254740993"}' },
    });

    expect(jsonParse).toHaveBeenCalledWith('{"orderId":"9007199254740993"}');
    expect(wrapper.find('.viewer-stub').attributes('data-value')).toBe(
      '{"orderId":"9007199254740993"}',
    );
  });

  it('emits a typed value event for clicked JSON items', async () => {
    const wrapper = mount(JsonViewer, { props: { value: {} } });

    await wrapper.find('.jv-item').trigger('click');

    expect(wrapper.emitted('valueClick')).toEqual([
      [
        expect.objectContaining({
          depth: 2,
          path: 'order.id',
          value: 123,
        }),
      ],
    ]);
    expect(wrapper.emitted('click')).toHaveLength(1);
  });

  it('forwards copied and key events from the viewer boundary', async () => {
    const wrapper = mount(JsonViewer, { props: { value: {} } });

    await wrapper.find('.emit-copy').trigger('click');
    await wrapper.find('.emit-key').trigger('click');

    expect(wrapper.emitted('copied')?.[0]?.[0]).toEqual(
      expect.objectContaining({ action: 'copy', text: '123' }),
    );
    expect(wrapper.emitted('keyClick')).toEqual([['orderId']]);
  });

  it('ignores JSON items without a path contract', async () => {
    const wrapper = mount(JsonViewer, { props: { value: {} } });

    await wrapper.find('.jv-item-without-path').trigger('click');

    expect(wrapper.emitted('valueClick')).toBeUndefined();
    expect(wrapper.emitted('click')).toBeUndefined();
  });

  it('falls back to an empty object for invalid JSON strings', () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    jsonParse.mockImplementation(() => {
      throw new SyntaxError('invalid JSON');
    });

    const wrapper = mount(JsonViewer, { props: { value: '{invalid}' } });

    expect(wrapper.find('.viewer-stub').attributes('data-value')).toBe('{}');
    expect(consoleError).toHaveBeenCalledWith(
      'JSON parse error:',
      expect.any(SyntaxError),
    );
    consoleError.mockRestore();
  });
});
