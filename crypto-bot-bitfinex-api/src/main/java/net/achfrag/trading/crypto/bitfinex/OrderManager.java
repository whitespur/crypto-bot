package net.achfrag.trading.crypto.bitfinex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.achfrag.trading.crypto.bitfinex.entity.APIException;
import net.achfrag.trading.crypto.bitfinex.entity.BitfinexOrder;
import net.achfrag.trading.crypto.bitfinex.entity.ExchangeOrder;
import net.achfrag.trading.crypto.bitfinex.entity.ExchangeOrderState;

public class OrderManager extends AbstractSimpleCallbackManager<ExchangeOrder> {

	/**
	 * The orders
	 */
	private final List<ExchangeOrder> orders;
	
	/**
	 * The api broker
	 */
	private BitfinexApiBroker bitfinexApiBroker;

	/**
	 * The order timeout
	 */
	private final long TIMEOUT_IN_SECONDS = 120;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(OrderManager.class);


	public OrderManager(final BitfinexApiBroker bitfinexApiBroker) {
		super(bitfinexApiBroker.getExecutorService());
		this.bitfinexApiBroker = bitfinexApiBroker;
		this.orders = new ArrayList<>();
	}
	
	/**
	 * Clear all orders
	 */
	public void clear() {
		synchronized (orders) {
			orders.clear();	
		}
	}

	/**
	 * Get the list with exchange orders
	 * @return
	 * @throws APIException 
	 */
	public List<ExchangeOrder> getOrders() throws APIException {		
		synchronized (orders) {
			return orders;
		}
	}
	
	/**
	 * Update a exchange order
	 * @param exchangeOrder
	 */
	public void updateOrder(final ExchangeOrder exchangeOrder) {
		
		synchronized (orders) {
			// Replace order 
			orders.removeIf(o -> o.getOrderId() == exchangeOrder.getOrderId());
			
			// Remove canceled orders
			if(exchangeOrder.getState() != ExchangeOrderState.STATE_CANCELED) {
				orders.add(exchangeOrder);
			}
						
			orders.notifyAll();
		}
		
		notifyCallbacks(exchangeOrder);
	}
	
	
	/**
	 * Cancel a order
	 * @param id
	 * @throws APIException, InterruptedException 
	 */
	public void placeOrderAndWaitUntilActive(final BitfinexOrder order) throws APIException, InterruptedException {
		
		if(! bitfinexApiBroker.isAuthenticated()) {
			logger.error("Unable to wait for order {}, connection is not authenticated", order);
			return;
		}
		
		order.setApikey(bitfinexApiBroker.getApiKey());
		
		final CountDownLatch waitLatch = new CountDownLatch(1);
		
		final Consumer<ExchangeOrder> ordercallback = (o) -> {
			if(o.getCid() == order.getCid()) {
				waitLatch.countDown();
			}
		};
		
		bitfinexApiBroker.getOrderManager().registerCallback(ordercallback);
		
		try {
			logger.info("Place new order: {}", order);
			bitfinexApiBroker.placeOrder(order);
			
			waitLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
			
			if(waitLatch.getCount() != 0) {
				throw new APIException("Timeout while waiting for order");
			}		
		} catch (Exception e) {
			throw e;
		} finally {
			bitfinexApiBroker.getOrderManager().removeCallback(ordercallback);
		}
	}
	
	/**
	 * Cancel a order
	 * @param id
	 * @throws APIException, InterruptedException 
	 */
	public void cancelOrderAndWaitForCompletion(final long id) throws APIException, InterruptedException {
		
		if(! bitfinexApiBroker.isAuthenticated()) {
			logger.error("Unable to cancel order {}, connection is not authenticated", id);
			return;
		}
		
		final CountDownLatch waitLatch = new CountDownLatch(1);
		
		final Consumer<ExchangeOrder> ordercallback = (o) -> {
			if(o.getOrderId() == id && o.getState() == ExchangeOrderState.STATE_CANCELED) {
				waitLatch.countDown();
			}
		};
		
		bitfinexApiBroker.getOrderManager().registerCallback(ordercallback);
		
		try {
			logger.info("Cancel order: {}", id);
			bitfinexApiBroker.cancelOrder(id);
			waitLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
			
			if(waitLatch.getCount() != 0) {
				throw new APIException("Timeout while waiting for order");
			}
			
		} catch (Exception e) {
			throw e;
		} finally {
			bitfinexApiBroker.getOrderManager().removeCallback(ordercallback);
		}
	}
}
