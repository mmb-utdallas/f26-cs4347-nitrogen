import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Fine rules shared by check-in (LoanService) and the Fines feature.
 *
 *   fine = $0.25 x (number of calendar days after the due date)
 *
 * - Returned (or still out) on or before the due date: $0.00.
 * - BigDecimal is used instead of double so money is exact (0.1 + 0.2 problem).
 */
public final class FineCalculator {

    /** $0.25 per day late. */
    public static final BigDecimal DAILY_RATE = new BigDecimal("0.25");

    /** $0.00 with two decimal places, matching FINES.Fine_amt DECIMAL(10,2). */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private FineCalculator() { }

    /** Number of whole calendar days after the due date (never negative). */
    public static long daysLate(LocalDate dueDate, LocalDate endDate) {
        long days = ChronoUnit.DAYS.between(dueDate, endDate);
        return Math.max(days, 0);
    }

    /**
     * Fine for a loan.
     *
     * @param dueDate the loan's Due_date
     * @param endDate the Date_in if the book was returned, otherwise today's date
     *                (fine accrued so far for a book that is still out)
     */
    public static BigDecimal calculateFine(LocalDate dueDate, LocalDate endDate) {
        long days = daysLate(dueDate, endDate);
        return DAILY_RATE.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.UNNECESSARY);
    }
}
