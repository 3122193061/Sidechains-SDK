package com.horizen.node;

import com.horizen.box.Box;
import com.horizen.proposition.Proposition;
import com.horizen.transaction.BoxTransaction;
import scala.sys.Prop;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface NodeMemoryPool {
    List<BoxTransaction<Proposition, Box<Proposition>>> getTransactions();

    List<BoxTransaction<Proposition, Box<Proposition>>> getTransactions(
            Comparator<BoxTransaction<Proposition, Box<Proposition>>> c,
            int limit);

    /**
     * @deprecated use {@link #getTransactionsSortedByFeeRate(int)} instead.
     */
    @Deprecated
    List<BoxTransaction<Proposition, Box<Proposition>>> getTransactionsSortedByFee(int limit);

    List<BoxTransaction<Proposition, Box<Proposition>>> getTransactionsSortedByFeeRate(int limit);

    int getSize();

    Optional<BoxTransaction<Proposition, Box<Proposition>>> getTransactionById(String transactionId);
}
