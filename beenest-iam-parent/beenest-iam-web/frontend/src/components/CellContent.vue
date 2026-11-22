<script lang="ts">
import { defineComponent, h, type PropType, type VNode } from 'vue';

export default defineComponent({
  name: 'CellContent',
  props: {
    content: { type: [Object, String, Number, Boolean] as PropType<VNode | string | number | boolean | null>, default: null },
  },
  setup(props) {
    return () => {
      const content = props.content;
      if (content === null || content === undefined || content === '') {
        return h('span', { class: 'muted' }, '—');
      }
      if (typeof content === 'object') return content;
      return String(content);
    };
  },
});
</script>
