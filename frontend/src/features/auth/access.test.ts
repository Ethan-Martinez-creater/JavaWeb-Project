import { describe, expect, it } from 'vitest'
import { decideAccess } from '../../router/access'

describe('route access rules', () => {
    it('sends an anonymous visitor to login and remembers the destination', () => {
        expect(decideAccess({ roles: ['USER'] }, '/me/orders', false, [])).toEqual({
            kind: 'login',
            redirect: '/me/orders',
        })
    })

    it('allows only the listed role and denies a logged-in user without it', () => {
        expect(decideAccess({ roles: ['ORGANIZER'] }, '/organizer/events', true, ['USER'])).toEqual(
            {
                kind: 'forbidden',
            },
        )
        expect(
            decideAccess({ roles: ['ORGANIZER'] }, '/organizer/events', true, ['ORGANIZER']),
        ).toEqual({ kind: 'allow' })
    })

    it('leaves public event pages available without a session', () => {
        expect(decideAccess({}, '/', false, [])).toEqual({ kind: 'allow' })
    })
})
