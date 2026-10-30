package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.domain.analytics.DeliveryAdjustmentFold
import cloud.trotter.log.gig.domain.analytics.PayAdjustmentFold
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.ReceiptRepriceFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryCorrectionsTest {
    @Test
    fun `pay corrections preserve frozen economics and the legacy suspect-receipt gap`() {
        val machine = row()
        val manual = machine.copy(payBasis = PayBasis.MANUAL, realizedMiles = null, netProfit = 10.0)
        val uncosted = machine.copy(frozenCostPerMile = null, netProfit = null)
        val suspect = machine.copy(
            payBasis = PayBasis.SUSPECT_FULL_RECEIPT, originalPayBasis = PayBasis.SUSPECT_FULL_RECEIPT,
            realizedPay = null, tip = null, basePay = null, netProfit = -1.25,
        )
        val fold = PayAdjustmentFold(machine.eventSequenceId, 12.0)
        val cases = listOf(
            Case("machine", machine, fold, CorrectionOutcome.Write(machine.copy(
                realizedPay = 12.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 10.75, driverAdjustedAt = 6_000,
            ))),
            Case("manual without miles", manual, fold, CorrectionOutcome.Write(manual.copy(
                realizedPay = 12.0, netProfit = 12.0, driverAdjustedAt = 6_000,
            ))),
            Case("machine without frozen cpm", uncosted, fold, CorrectionOutcome.Write(uncosted.copy(
                realizedPay = 12.0, payBasis = PayBasis.USER_CORRECTED, netProfit = null, driverAdjustedAt = 6_000,
            ))),
            // PAY_ADJUSTMENT intentionally has no SUSPECT_FULL_RECEIPT guard today.
            Case("suspect receipt still accepts legacy pay", suspect, fold, CorrectionOutcome.Write(suspect.copy(
                realizedPay = 12.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 10.75, driverAdjustedAt = 6_000,
            ))),
        )
        for (case in cases) {
            assertOutcome(case.name, case.expected, DeliveryCorrections.applyPay(case.input, case.fold, 6_000))
        }
    }

    @Test
    fun `delivery correction field combinations return complete rows and ordered follow-ups`() {
        val input = row()
        val noKey = input.copy(storeKey = null)
        val cash = input.copy(cashTip = 6.0)
        val manual = input.copy(payBasis = PayBasis.MANUAL, originalPayBasis = PayBasis.MANUAL)
        val manualUncosted = manual.copy(frozenCostPerMile = null, netProfit = 10.0)
        val sessionless = input.copy(sessionId = null, cashTip = 4.0)
        val suspect = input.copy(
            originalPayBasis = PayBasis.SUSPECT_FULL_RECEIPT, payBasis = PayBasis.SUSPECT_FULL_RECEIPT,
            realizedPay = null, tip = null, basePay = null, netProfit = -1.25,
        )
        val payless = input.copy(realizedPay = null, netProfit = -1.25)
        val fold = DeliveryAdjustmentFold(input.eventSequenceId)
        val sweep = listOf(CorrectionFollowUp.SweepStoreIfUnreferenced("store:prior"))
        val cases = listOf(
            Case("every field", input, fold.copy(
                newStoreName = "Bill Millers", newPay = 20.0, newTip = 5.0, newCashTip = 3.0, newMiles = 8.0,
            ), CorrectionOutcome.Write(input.copy(
                storeName = "Bill Millers", realizedPay = 20.0, tip = 5.0, cashTip = 3.0, realizedMiles = 8.0,
                payBasis = PayBasis.USER_CORRECTED, netProfit = 18.0, storeKey = null, storeKeyPinned = 1,
                driverAdjustedAt = 6_000,
            ), sweep)),
            Case("store with prior key", input, fold.copy(newStoreName = "Bill Millers"), CorrectionOutcome.Write(
                input.copy(storeName = "Bill Millers", storeKey = null, storeKeyPinned = 1), sweep,
            )),
            Case("store without prior key", noKey, fold.copy(newStoreName = "Bill Millers"), CorrectionOutcome.Write(
                noKey.copy(storeName = "Bill Millers", storeKey = null, storeKeyPinned = 1),
            )),
            Case("cash only", input, fold.copy(newCashTip = 6.0), CorrectionOutcome.Write(input.copy(cashTip = 6.0))),
            Case("zero cash clears", cash, fold.copy(newCashTip = 0.0), CorrectionOutcome.Write(cash.copy(cashTip = null))),
            Case("manual pay and miles", manual, fold.copy(newPay = 12.0, newMiles = 8.0), CorrectionOutcome.Write(
                manual.copy(realizedPay = 12.0, realizedMiles = 8.0, netProfit = 10.0, driverAdjustedAt = 6_000),
            )),
            Case("note only still writes", input, fold, CorrectionOutcome.Write(input.copy())),
            Case("sessionless keeps existing cash", sessionless, fold.copy(newPay = 12.0, newCashTip = 6.0),
                CorrectionOutcome.Write(sessionless.copy(
                    realizedPay = 12.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 10.75, driverAdjustedAt = 6_000,
                ), listOf(CorrectionFollowUp.CashIgnoredOnSessionlessRow))),
            Case("suspect blocks pay and tip but renames", suspect,
                fold.copy(newPay = 25.0, newTip = 5.0, newStoreName = "Bill Millers"), CorrectionOutcome.Write(
                    suspect.copy(storeName = "Bill Millers", storeKey = null, storeKeyPinned = 1),
                    listOf(CorrectionFollowUp.PayTipBlockedOnSuspectReceipt) + sweep,
                )),
            Case("miles only keeps basis", input, fold.copy(newMiles = 8.0), CorrectionOutcome.Write(
                input.copy(realizedMiles = 8.0, netProfit = 8.0, driverAdjustedAt = 6_000),
            )),
            Case("payless miles still carry cost #1133", payless, fold.copy(newMiles = 8.0), CorrectionOutcome.Write(
                payless.copy(realizedMiles = 8.0, netProfit = -2.0, driverAdjustedAt = 6_000),
            )),
            Case("tip only owns money without changing basis", input, fold.copy(newTip = 5.0), CorrectionOutcome.Write(
                input.copy(tip = 5.0, driverAdjustedAt = 6_000),
            )),
            Case("manual without frozen cpm", manualUncosted, fold.copy(newPay = 12.0, newMiles = 8.0),
                CorrectionOutcome.Write(manualUncosted.copy(
                    realizedPay = 12.0, realizedMiles = 8.0, netProfit = 12.0, driverAdjustedAt = 6_000,
                ))),
        )
        for (case in cases) {
            assertOutcome(case.name, case.expected, DeliveryCorrections.applyDelivery(case.input, case.fold, 6_000))
        }
    }

    @Test
    fun `receipt reprices respect ownership before cent equality and preserve unrelated columns`() {
        val input = row().copy(cashTip = 6.0)
        val fold = reprice()
        val priced = input.copy(realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE)
        val tipChanged = priced.copy(tip = 6.0)
        val baseChanged = priced.copy(basePay = 8.70)
        val adjusted = input.copy(driverAdjustedAt = 3_200)
        val corrected = input.copy(payBasis = PayBasis.USER_CORRECTED)
        val manual = input.copy(payBasis = PayBasis.MANUAL)
        val noMiles = input.copy(realizedMiles = null)
        val noCost = input.copy(frozenCostPerMile = null)
        val sameCents = priced.copy(realizedPay = 16.704, tip = 7.004, basePay = 9.704)
        val nullParts = priced.copy(tip = null, basePay = null)
        val ownedAndPriced = priced.copy(driverAdjustedAt = 3_200)
        val cases = listOf(
            Case("offer becomes drop share, cash untouched", input, fold, CorrectionOutcome.Write(input.copy(
                realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
                netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
            ))),
            Case("same cents", priced, fold, CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(1_670))),
            Case("different tip", tipChanged, fold, CorrectionOutcome.Write(tipChanged.copy(
                tip = 7.0, netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
            ))),
            Case("different base pay", baseChanged, fold, CorrectionOutcome.Write(baseChanged.copy(
                basePay = 9.70, netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
            ))),
            Case("driver marker", adjusted, fold, CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.OFFER_PAY, true))),
            Case("user corrected", corrected, fold, CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.USER_CORRECTED, false))),
            Case("manual", manual, fold, CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.MANUAL, false))),
            Case("null miles", noMiles, fold, CorrectionOutcome.Write(noMiles.copy(
                realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
                netProfit = null, receiptRepricedAt = 3_400,
            ))),
            Case("null frozen cpm", noCost, fold, CorrectionOutcome.Write(noCost.copy(
                realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
                netProfit = null, receiptRepricedAt = 3_400,
            ))),
            Case("rounds all three money terms to cents", sameCents, fold, CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(1_670))),
            Case("two null terms equal", nullParts, fold.copy(tip = null, basePay = null),
                CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(1_670))),
            Case("null differs from zero", nullParts, fold.copy(tip = 0.0, basePay = null), CorrectionOutcome.Write(
                nullParts.copy(tip = 0.0, netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400),
            )),
            Case("driver guard wins over already priced", ownedAndPriced, fold,
                CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.DROP_SHARE, true))),
        )
        for (case in cases) {
            assertOutcome(case.name, case.expected, DeliveryCorrections.applyReceiptReprice(case.input, case.fold))
        }
    }

    @Test
    fun `re-pricing a MANUAL row keeps the MANUAL basis and the net-additive policy (review F1)`() {
        val input = row().copy(
            sessionId = "MISSED", payBasis = PayBasis.MANUAL, originalPayBasis = PayBasis.MANUAL,
            realizedPay = 9.0, realizedMiles = null, frozenCostPerMile = null, netProfit = 9.0,
        )
        assertEquals(9.0, input.netProfit!!, 0.0)
        assertWrite(input.copy(realizedPay = 12.0, netProfit = 12.0, driverAdjustedAt = 6_000),
            DeliveryCorrections.applyPay(input, PayAdjustmentFold(input.eventSequenceId, 12.0), 6_000))
    }

    @Test
    fun `a zero cash adjustment clears cash and does not claim pay ownership`() {
        val before = row()
        var current = before
        for (cash in listOf(0.0, 6.0, 0.0)) {
            current = assertWrite(current.copy(cashTip = if (cash == 0.0) null else cash),
                DeliveryCorrections.applyDelivery(current, DeliveryAdjustmentFold(current.eventSequenceId, newCashTip = cash), 3_100))
            assertNull(current.driverAdjustedAt)
            assertEquals(before.realizedPay, current.realizedPay)
            assertEquals(before.netProfit, current.netProfit)
        }
    }

    @Test
    fun `a REDUNDANT re-price is a no-op at the projector — no rewrite, no marker (#1033 round 8)`() {
        val input = row().copy(realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE)
        val before = input.copy()
        assertNull(input.receiptRepricedAt)
        assertEquals(CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(1_670)),
            DeliveryCorrections.applyReceiptReprice(input, reprice()))
        assertEquals(before, input)
    }

    @Test
    fun `a driver TIP-ONLY edit is never overwritten by a later re-price (#1033 round 6)`() {
        val input = row()
        val expected = input.copy(tip = 5.0, driverAdjustedAt = 3_200)
        val edited = assertWrite(expected,
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newTip = 5.0), 3_200))
        assertEquals(CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.OFFER_PAY, true)),
            DeliveryCorrections.applyReceiptReprice(edited, reprice()))
        assertEquals(expected, edited)
        assertNull(edited.receiptRepricedAt)
    }

    @Test
    fun `a legacy PAY_ADJUSTMENT also blocks a later re-price (#1033 round 6)`() {
        val input = row()
        val expected = input.copy(realizedPay = 11.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 9.75, driverAdjustedAt = 3_200)
        val edited = assertWrite(expected,
            DeliveryCorrections.applyPay(input, PayAdjustmentFold(input.eventSequenceId, 11.0), 3_200))
        assertEquals(CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.USER_CORRECTED, true)),
            DeliveryCorrections.applyReceiptReprice(edited, reprice()))
        assertEquals(expected, edited)
        assertNull(edited.receiptRepricedAt)
    }

    @Test
    fun `a store-name-only edit is NOT monetary and leaves the re-price path open (#1033 round 6)`() {
        val input = row()
        val edited = assertWrite(input.copy(storeName = "Bill Millers", storeKey = null, storeKeyPinned = 1),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newStoreName = "Bill Millers"), 3_200),
            listOf(CorrectionFollowUp.SweepStoreIfUnreferenced("store:prior")))
        assertNull(edited.driverAdjustedAt)
        assertWrite(edited.copy(
            realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
            netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
        ), DeliveryCorrections.applyReceiptReprice(edited, reprice()))
    }

    @Test
    fun `a cashTip-only edit does not flip payBasis and leaves netProfit byte-identical (D6c)`() {
        val input = row()
        assertWrite(input.copy(cashTip = 6.0),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newCashTip = 6.0), 6_000))
    }

    @Test
    fun `a MANUAL row edited via DELIVERY_ADJUSTMENT stays MANUAL with the net-additive policy (#688)`() {
        val input = row().copy(
            sessionId = "MISSED", payBasis = PayBasis.MANUAL, originalPayBasis = PayBasis.MANUAL,
            realizedPay = 9.0, realizedMiles = null, frozenCostPerMile = null, netProfit = 9.0,
        )
        assertWrite(input.copy(realizedPay = 12.0, cashTip = 2.0, netProfit = 12.0, driverAdjustedAt = 6_000),
            DeliveryCorrections.applyDelivery(input,
                DeliveryAdjustmentFold(input.eventSequenceId, newPay = 12.0, newCashTip = 2.0), 6_000))
    }

    @Test
    fun `a note-only DELIVERY_ADJUSTMENT is a no-op apply (VET F6)`() {
        val input = row()
        // Notes live in the event payload; a fold with no changed columns still writes.
        assertWrite(input.copy(), DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId), 6_000))
    }

    @Test
    fun `a DELIVERY_ADJUSTMENT cash edit is dropped on a null-session row while other fields apply (#705 F3)`() {
        val input = row().copy(sessionId = null, storeKey = null)
        assertNull(input.sessionId)
        assertWrite(input.copy(storeName = "Bill Millers", storeKeyPinned = 1),
            DeliveryCorrections.applyDelivery(input,
                DeliveryAdjustmentFold(input.eventSequenceId, newStoreName = "Bill Millers", newCashTip = 5.0), 6_000),
            listOf(CorrectionFollowUp.CashIgnoredOnSessionlessRow))
    }

    @Test
    fun `a DELIVERY_ADJUSTMENT pay-tip edit is blocked on a SUSPECT_FULL_RECEIPT row while store-cash still apply (#705 F5)`() {
        val input = row().copy(
            payBasis = PayBasis.SUSPECT_FULL_RECEIPT, originalPayBasis = PayBasis.SUSPECT_FULL_RECEIPT,
            realizedPay = null, tip = null, basePay = null, netProfit = -1.25, storeKey = null,
        )
        assertNull(input.realizedPay)
        assertWrite(input.copy(storeName = "Bill Millers", cashTip = 3.0, storeKeyPinned = 1),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(
                input.eventSequenceId, newStoreName = "Bill Millers", newPay = 25.0, newTip = 5.0, newCashTip = 3.0,
            ), 6_000), listOf(CorrectionFollowUp.PayTipBlockedOnSuspectReceipt))
    }

    @Test
    fun `criterion 6 - a newMiles correction wins realizedMiles for money but leaves the machine legs intact (#688)`() {
        val input = row().copy(realizedMiles = 2.9, milesToStore = 0.5, milesToDropoff = 2.4, netProfit = 10.0 - 2.9 * 0.25)
        assertEquals(0.5, input.milesToStore!!, 0.0)
        assertEquals(2.4, input.milesToDropoff!!, 0.0)
        val edited = assertWrite(input.copy(realizedMiles = 8.0, netProfit = 8.0, driverAdjustedAt = 6_000),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newMiles = 8.0), 6_000))
        assertEquals(2.9, edited.milesToStore!! + edited.milesToDropoff!!, 1e-6)
    }

    @Test
    fun `delivery pay then receipt reprice preserves driver ownership`() {
        val input = row()
        val expected = input.copy(realizedPay = 21.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 19.75, driverAdjustedAt = 3_100)
        val edited = assertWrite(expected,
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newPay = 21.0), 3_100))
        assertEquals(CorrectionOutcome.Skip(RepriceSkip.DriverOwned(PayBasis.USER_CORRECTED, true)),
            DeliveryCorrections.applyReceiptReprice(edited, reprice()))
        assertEquals(expected, edited)
    }

    @Test
    fun `receipt reprice then delivery pay keeps the receipt marker and original basis`() {
        val input = row()
        val repriced = assertWrite(input.copy(
            realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
            netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
        ), DeliveryCorrections.applyReceiptReprice(input, reprice()))
        assertWrite(repriced.copy(realizedPay = 21.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 19.75, driverAdjustedAt = 6_000),
            DeliveryCorrections.applyDelivery(repriced, DeliveryAdjustmentFold(input.eventSequenceId, newPay = 21.0), 6_000))
    }

    @Test
    fun `delivery cash then receipt reprice preserves cash without claiming pay ownership`() {
        val input = row()
        val edited = assertWrite(input.copy(cashTip = 6.0),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newCashTip = 6.0), 3_100))
        assertWrite(edited.copy(
            realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
            netProfit = 16.70 - 5.0 * 0.25, receiptRepricedAt = 3_400,
        ), DeliveryCorrections.applyReceiptReprice(edited, reprice()))
    }

    @Test
    fun `redundant receipt reprice then changed receipt reprice still writes`() {
        val input = row().copy(
            realizedPay = 16.70, tip = 7.0, basePay = 9.70, payBasis = PayBasis.DROP_SHARE,
            netProfit = 16.70 - 5.0 * 0.25,
        )
        assertEquals(CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(1_670)),
            DeliveryCorrections.applyReceiptReprice(input, reprice()))
        // A Skip has no replacement row; the next apply sees the same input.
        assertWrite(input.copy(realizedPay = 20.0, tip = 10.30, netProfit = 18.75, receiptRepricedAt = 3_500),
            DeliveryCorrections.applyReceiptReprice(input, reprice().copy(realizedPay = 20.0, tip = 10.30, repricedAt = 3_500)))
    }

    @Test
    fun `delivery miles then delivery pay preserves the first-fold basis and machine legs`() {
        val input = row()
        val milesEdited = assertWrite(input.copy(realizedMiles = 8.0, netProfit = 8.0, driverAdjustedAt = 5_000),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(input.eventSequenceId, newMiles = 8.0), 5_000))
        assertWrite(milesEdited.copy(realizedPay = 20.0, payBasis = PayBasis.USER_CORRECTED, netProfit = 18.0, driverAdjustedAt = 6_000),
            DeliveryCorrections.applyDelivery(milesEdited, DeliveryAdjustmentFold(input.eventSequenceId, newPay = 20.0), 6_000))
    }

    @Test
    fun `combined rejections precede a sweep carrying the prior store key`() {
        // The original basis, not the current basis, guards suspect-receipt pay and tip edits.
        val input = row().copy(sessionId = null, originalPayBasis = PayBasis.SUSPECT_FULL_RECEIPT, cashTip = 4.0)
        assertWrite(input.copy(storeName = "Bill Millers", storeKey = null, storeKeyPinned = 1),
            DeliveryCorrections.applyDelivery(input, DeliveryAdjustmentFold(
                input.eventSequenceId, newStoreName = "Bill Millers", newPay = 25.0, newTip = 5.0, newCashTip = 3.0,
            ), 6_000), listOf(
                CorrectionFollowUp.PayTipBlockedOnSuspectReceipt,
                CorrectionFollowUp.CashIgnoredOnSessionlessRow,
                CorrectionFollowUp.SweepStoreIfUnreferenced("store:prior"),
            ))
    }

    private data class Case<F>(
        val name: String,
        val input: DeliveryRecordEntity,
        val fold: F,
        val expected: CorrectionOutcome,
    )

    private fun assertOutcome(name: String, expected: CorrectionOutcome, actual: CorrectionOutcome) {
        when (expected) {
            is CorrectionOutcome.Write -> assertWrite(expected.row, actual, expected.followUps, name)
            is CorrectionOutcome.Skip -> assertEquals(name, expected, actual)
        }
    }

    private fun assertWrite(
        expected: DeliveryRecordEntity,
        outcome: CorrectionOutcome,
        followUps: List<CorrectionFollowUp> = emptyList(),
        message: String = "correction writes the complete expected row",
    ): DeliveryRecordEntity {
        assertTrue(message, outcome is CorrectionOutcome.Write)
        outcome as CorrectionOutcome.Write
        val actual = outcome.row
        assertEquals(message, expected, actual)
        assertEquals(message, followUps, outcome.followUps)
        return actual
    }

    private fun reprice() = ReceiptRepriceFold(
        jobId = "J1", taskId = "T1", realizedPay = 16.70, tip = 7.0, basePay = 9.70, repricedAt = 3_400,
    )

    /** Explicit defaults include provenance columns so full-row equality catches accidental rewrites. */
    private fun row() = DeliveryRecordEntity(
        eventSequenceId = 42,
        sessionId = "S1",
        platform = "doordash",
        jobId = "J1",
        taskId = "T1",
        storeName = "StoreX",
        customerHash = "customer-hash",
        addressHash = "address-hash",
        phaseStartedAt = 2_400,
        arrivedAt = 2_800,
        completedAt = 3_000,
        deadlineMillis = 3_600,
        realizedPay = 10.0,
        payBasis = PayBasis.OFFER_PAY,
        tip = 2.0,
        basePay = 8.0,
        odometerAtCompletion = 105.0,
        realizedMiles = 5.0,
        realizedMinutes = 10.0,
        frozenCostPerMile = 0.25,
        frozenFuelPerMile = 0.10,
        frozenNonFuelPerMile = 0.15,
        netProfit = 8.75,
        costBasis = "OFFER_FROZEN",
        cashTip = null,
        originalPayBasis = PayBasis.OFFER_PAY,
        storeKey = "store:prior",
        payoutStoreForms = "[\"StoreX\"]",
        storeKeyPinned = 0,
        milesToStore = 1.0,
        milesToDropoff = 4.0,
        milesBasis = "LEG_SUM",
        sessionAssigned = 0,
        receiptRepricedAt = null,
        driverAdjustedAt = null,
        jobOfferCount = 1,
        soleOfferHash = "offer-hash",
        odometerAtArrival = 104.5,
    )
}
