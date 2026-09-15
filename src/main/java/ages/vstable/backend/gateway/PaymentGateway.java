package ages.vstable.backend.gateway;



public interface PaymentGateway {
    Boolean getPaymentStatus(int paymentID);
}
