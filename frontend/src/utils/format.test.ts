import { describe, expect, it } from 'vitest'
import { candidateLead, categoryLabel, shortFingerprint, simpleExceptionName, splitIncidentTitle } from './format'

describe('format helpers', () => {
  it('only calls a HIGH-confidence candidate the most likely cause', () => {
    expect(candidateLead('HIGH')).toBe('Most likely cause based on available evidence')
    expect(candidateLead('MEDIUM')).toBe('Potential root cause')
    expect(candidateLead('LOW')).toBe('Potential root cause')
  })

  it('labels categories and missing analyses', () => {
    expect(categoryLabel('DATABASE')).toBe('Database / connectivity')
    expect(categoryLabel('UNKNOWN')).toBe('Insufficient evidence')
    expect(categoryLabel(null)).toBe('Not analyzed')
  })

  it('shortens identifiers for tables', () => {
    expect(shortFingerprint('9910dd1f3cee53f5cd2fb547aac790bf')).toBe('9910dd1f3cee')
    expect(simpleExceptionName('java.net.SocketTimeoutException')).toBe('SocketTimeoutException')
    expect(simpleExceptionName('KeyError')).toBe('KeyError')
    expect(simpleExceptionName(null)).toBeNull()
  })

  it('splits default incident titles into the message and the exception type', () => {
    expect(splitIncidentTitle('java.net.SocketTimeoutException: Payment authorization failed')).toEqual({
      headline: 'Payment authorization failed',
      exceptionType: 'java.net.SocketTimeoutException',
    })
    expect(splitIncidentTitle('com.shop.checkout.PaymentFailedException: Order could not be completed: payment failed')).toEqual({
      headline: 'Order could not be completed: payment failed',
      exceptionType: 'com.shop.checkout.PaymentFailedException',
    })
    expect(splitIncidentTitle('KeyError: sku')).toEqual({ headline: 'sku', exceptionType: 'KeyError' })
    // Titles without an exception type, including ones that contain a colon, stay whole.
    expect(splitIncidentTitle('Order could not be completed: payment failed')).toEqual({
      headline: 'Order could not be completed: payment failed',
      exceptionType: null,
    })
    expect(splitIncidentTitle('Disk almost full')).toEqual({ headline: 'Disk almost full', exceptionType: null })
  })
})
