<template>
  <component
    :is="renderComponent"
    :resource-type="resourceType"
    :resource-code="resourceCode"
    :project-code="projectCode"
    :ajax-prefix="ajaxPrefix"
    :title="title"
  />
</template>

<script>
import GroupTable from './group-table.vue';
import PermissionTips from './permission-tips.vue';

export default {
  props: {
    title: {
      type: String,
      default: '',
    },
    resourceType: {
      type: String,
      default: '',
    },
    resourceCode: {
      type: String,
      default: '',
    },
    projectCode: {
      type: String,
      default: '',
    },
    ajaxPrefix: {
      type: String,
      default: '',
    },
  },
  computed: {
    renderComponent() {
      return this.resourceType === 'project' ? PermissionTips : GroupTable;
    },
  },
};
</script>
