import { defineStore } from 'pinia'

export const useMonitoringPoliciesStore = defineStore('monitoringPoliciesStore', {
  state: () => ({
    policy: {
      id: '',
      name: '',
      tags: '',
      rules: ''
    }
  })
})
