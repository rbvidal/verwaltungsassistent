package verwaltungsassistent.web.demo;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps the shared {@link AiFacade} bean with the demo concurrency guard so
 * that every expensive reasoning operation (assistant, e-mail analysis,
 * decision generation, case briefing, evaluation) passes through one
 * application-level slot mechanism.
 *
 * <p>The deterministic e2e facade is excluded so tests are not throttled.
 * The guard only wraps the {@code answer(...)} call; navigation and all other
 * functionality remain unrestricted.</p>
 */
@Component
@Profile("demo")
public class AiFacadeConcurrencyPostProcessor implements BeanPostProcessor {

    private final DemoAiConcurrencyGuard guard;

    public AiFacadeConcurrencyPostProcessor(DemoAiConcurrencyGuard guard) {
        this.guard = guard;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AiFacade
                && !(bean.getClass().getName().contains("Deterministic"))
                && !Proxy.isProxyClass(bean.getClass())) {
            AiFacade delegate = (AiFacade) bean;
            return Proxy.newProxyInstance(
                    bean.getClass().getClassLoader(),
                    new Class<?>[]{AiFacade.class},
                    new GuardedInvocationHandler(delegate, guard));
        }
        return bean;
    }

    private static final class GuardedInvocationHandler implements InvocationHandler {
        private final AiFacade delegate;
        private final DemoAiConcurrencyGuard guard;

        private GuardedInvocationHandler(AiFacade delegate, DemoAiConcurrencyGuard guard) {
            this.delegate = delegate;
            this.guard = guard;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"answer".equals(method.getName())) {
                return method.invoke(delegate, args);
            }
            AiRequest request = (AiRequest) args[0];
            String userKey = userOf(request);
            if (!guard.tryAcquire(userKey)) {
                throw new AiCapacityExceededException();
            }
            try {
                return method.invoke(delegate, args);
            } finally {
                guard.release(userKey);
            }
        }

        private static String userOf(AiRequest request) {
            AiConversationContext ctx = request == null ? null : request.context();
            if (ctx != null && ctx.actorId() != null && !ctx.actorId().isBlank()) {
                return ctx.actorId();
            }
            return "anonymous";
        }
    }
}
