package io.knotra;

/**
 * 某次结构变更操作的已提交代际与其异步传播结算观察契约。
 *
 * <p>提供对本次变更所影响的受控组件、子挂载递归收敛及排空状态的异步或有界同步等待能力。</p>
 */
public interface Settlement extends Awaitable<SettlementReport> {

    /** 获取本次操作成功提交后的全局结构代际（Generation）版本号。 */
    long generation();
}
