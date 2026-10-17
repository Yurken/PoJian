package com.emerge.app.capture

/** parentIndex points to an earlier node in the visible, preorder scan; -1 is a root. */
internal data class PaymentNode(val text: String = "", val resourceId: String = "", val actionable: Boolean = false,
    val parentIndex: Int = -1)

internal object PaymentPagePolicy {
    private val actions = Regex("^(确认支付|立即支付|立即付款|确认付款|付款|支付|确认转账|转账|pay now|confirm payment|confirm transfer)([ ¥￥$：:0-9.,元]*)$", RegexOption.IGNORE_CASE)
    private val titles = setOf("收银台", "确认付款", "确认支付", "支付订单", "付款详情", "转账", "checkout", "payment")
    private val codes = setOf("付款码", "收款码", "收付款", "payment code", "receive money")
    private val fields = setOf("支付方式", "付款方式", "订单金额", "实付款", "收款人", "转账金额", "payment method", "total amount", "payee")
    private val protectedId = Regex("(^|_)(cashier|payment_password|pay_password|payment_qrcode|pay_qrcode|wallet_pay|transfer_confirm)(_|$)")
    private val codeId = Regex("(^|_)(qr_code|qrcode|bar_code|barcode)(_|$)")
    private val codeContext = setOf("向商家付款", "点击查看数字", "保存收款码", "收款记录")

    fun blocks(nodes: List<PaymentNode>): Boolean {
        val actionable = BooleanArray(nodes.size)
        nodes.forEachIndexed { index, node ->
            actionable[index] = node.actionable || (node.parentIndex in 0 until index && actionable[node.parentIndex])
        }
        val labels = nodes.map { it.text.trim().lowercase(java.util.Locale.ROOT) }.toSet()
        return nodes.withIndex().any { (index, node) ->
            protectedId.containsMatchIn(node.resourceId.substringAfterLast('/').lowercase(java.util.Locale.ROOT)) ||
                (actionable[index] && actions.matches(node.text.trim())) } ||
            (labels.any { it in codes } && nodes.withIndex().any { (index, node) ->
                codeId.containsMatchIn(node.resourceId.substringAfterLast('/').lowercase(java.util.Locale.ROOT)) ||
                    (actionable[index] && node.text.trim().lowercase(java.util.Locale.ROOT) in codes + codeContext)
            }) || (labels.any { it in titles } && labels.any { it in fields })
    }
}
