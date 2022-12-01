import { mount } from '@vue/test-utils'
import DetectedNodesTabContent from '@/components/Inventory/DetectedNodesTabContent.vue'
import { TimeUnit } from '@/types'

const tabContent = [
  {
    id: 1,
    label: 'Detected Node 1',
    metrics: [
      {
        type: 'latency',
        label: 'Latency',
        timestamp: 9,
        timeUnit: TimeUnit.MSecs,
        status: 'UP'
      },
      {
        type: 'uptime',
        label: 'Uptime',
        timestamp: 1667930274.660,
        timeUnit: TimeUnit.Secs,
        status: 'DOWN'
      },
      {
        type: 'status',
        label: 'Status',
        status: 'DOWN'
      }
    ],
    anchor: {
      profileValue: 75,
      profileLink: 'goto',
      locationValue: 'DefaultMinion',
      locationLink: 'goto',
      ipAddressValue: 25,
      ipAddressLink: 'goto',
      tagValue: 100,
      tagLink: 'goto'
    }
  }
]

let wrapper: any

describe('DetectedNodesTabContent component', () => {
  beforeAll(() => {
    wrapper = mount(DetectedNodesTabContent, {
      shallow: true,
      props: {
        tabContent
      }
    })
  })
  afterAll(() => {
    wrapper.unmount()
  }) 

  const tabComponents = [
    'icon',
    'heading',
    'metric-chip-list',
    'text-anchor-list',
    'icon-action-list'
  ]
  it.each(tabComponents)('should have "%s" components', (cmp) => {
    expect(wrapper.get(`[data-test="${cmp}"]`).exists()).toBe(true)
  })
})