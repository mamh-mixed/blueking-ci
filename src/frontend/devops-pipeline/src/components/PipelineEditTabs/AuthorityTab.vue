<template>
    <bk-permission
        :key="resourceCode"
        :resource-type="resourceType"
        :resource-code="resourceCode"
        :project-code="projectCode"
        :show-create-group="false"
        :resource-name="pipelineName"
    />
</template>

<script>
    import pipelineOperateMixin from '@/mixins/pipeline-operate-mixin'
    import { mapState } from 'vuex'

    export default {
        name: 'auth-tab',
        mixins: [pipelineOperateMixin],
        data () {
            return {
                resourceType: 'pipeline'
            }
        },
        computed: {
            ...mapState('atom', ['pipelineInfo']),
            projectCode () {
                return this.$route.params.projectId
            },
            resourceCode () {
                return this.$route.params.pipelineId
            },
            pipelineName () {
                return this.pipelineInfo?.pipelineName ?? ''
            }
        }
    }
</script>
