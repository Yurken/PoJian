package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class PaymentPagePolicyTest {
    @Test fun checkoutAndTransferControlsBlockTheWholePage() {
        for (nodes in listOf(
            listOf(PaymentNode("订单金额"), PaymentNode("¥128.00"), PaymentNode("确认支付", actionable = true)),
            listOf(PaymentNode("收款人"), PaymentNode("转账金额"), PaymentNode("确认转账", actionable = true)),
            listOf(PaymentNode("收银台"), PaymentNode("支付方式")),
            listOf(PaymentNode("付款码"), PaymentNode(resourceId = "com.app:id/qr_code")),
            listOf(PaymentNode(resourceId = "com.app:id/payment_password")),
            listOf(PaymentNode("Payment method"), PaymentNode("Pay now", actionable = true))
        )) assertTrue(nodes.toString(), PaymentPagePolicy.blocks(nodes))
    }

    @Test fun ArticlesAndOrdinaryShoppingNavigationAreNotPaymentPages() {
        assertFalse(PaymentPagePolicy.blocks(listOf(PaymentNode("如何安全使用支付功能？点击确认支付后需检查订单金额。"))))
        assertFalse(PaymentPagePolicy.blocks(listOf(PaymentNode("移动支付"), PaymentNode("支付方式的演变"), PaymentNode("点赞", actionable = true))))
        assertFalse(PaymentPagePolicy.blocks(listOf(PaymentNode("¥128.00"), PaymentNode("加入购物车", actionable = true))))
        for (title in listOf("付款码", "收款码", "收付款"))
            assertFalse(PaymentPagePolicy.blocks(listOf(PaymentNode(title), PaymentNode("这篇文章介绍它的历史。"))))
    }

    @Test fun actionablePaymentControlAndPaymentResourceCannotBeHiddenByLongContent() {
        assertTrue(PaymentPagePolicy.blocks(List(300) { PaymentNode("普通文字") } + PaymentNode("立即支付", actionable = true)))
        assertTrue(PaymentPagePolicy.blocks(listOf(PaymentNode(resourceId = "com.app:id/cashier_root"))))
    }

    @Test fun compositePaymentButtonInheritsOnlyItsOwnVisibleAncestors() {
        assertTrue(PaymentPagePolicy.blocks(listOf(
            PaymentNode("订单详情"), PaymentNode("合计 ¥128"),
            PaymentNode(actionable = true), PaymentNode(parentIndex = 2),
            PaymentNode("立即支付", parentIndex = 3))))
        assertFalse(PaymentPagePolicy.blocks(listOf(
            PaymentNode("收藏", actionable = true), PaymentNode("立即支付"))))
    }
}
