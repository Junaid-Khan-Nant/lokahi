import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createClient, VILLUS_CLIENT } from 'villus'
import DeviceTable from '@/components/Appliances/DeviceTable.vue'

describe('DeviceTable.vue', () => {
  describe.skip('Required columns', () => {
    const requiredColumns = [
      ['Device', 'col-device'],
      ['Latency', 'col-latency'],
      ['Uptime', 'col-uptime']
    ]

    it.each(requiredColumns)('should have "%s" column', (_, dataTest) => {
      const wrapper = mount(DeviceTable, { 
        global: { plugins: [createTestingPinia()] }
      })

      const elem = wrapper.find(`[data-test="${dataTest}"]`)
      expect(elem.exists()).toBe(true)
    })
  })
    
  it.skip('should have an empty table when there\'s no device', async () =>{
    const wrapper = mount(DeviceTable, { 
      global: { plugins: [createTestingPinia({
        initialState: { 
          deviceItems: []
        }
      })] }
    })
      
    const deviceItem = wrapper.find('[data-test="device-item"]')
    expect(deviceItem.exists()).toBe(false)
  })
    
  it('should display a list when there\'s device', async () => {
    const deviceItems = [{
      id: '1',
      name: 'device1',
      icmp_latency: 'latency1',
      snmp_uptime: 'uptime1'
    }]

    const wrapper = mount(DeviceTable, { 
      global: { plugins: [createTestingPinia({
        initialState: { 
          appliancesQueries  : { 
            listDevices: deviceItems
          }
        }
      })],
      provide: {
        [VILLUS_CLIENT as unknown as string]: createClient({
          url: 'http://test/graphql'
        })
      } }
    })
    
    const deviceItem = wrapper.find('[data-test="device-item"]')
    expect(deviceItem.exists()).toBe(true)
  })
})