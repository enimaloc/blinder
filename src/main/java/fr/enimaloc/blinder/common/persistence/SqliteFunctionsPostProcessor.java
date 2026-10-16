package fr.enimaloc.blinder.common.persistence;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Sous SQLite, fait créer les connexions physiques du pool Hikari par {@link SqliteFunctionsDataSource}, qui y
 * enregistre les fonctions Java attendues par {@link BlinderSqliteDialect}. Sans effet sur les autres bases.
 * Agit avant le démarrage du pool (paresseux : première connexion demandée).
 */
@Component
class SqliteFunctionsPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource hikari && hikari.getDataSource() == null
                && hikari.getJdbcUrl() != null && hikari.getJdbcUrl().startsWith("jdbc:sqlite:")) {
            hikari.setDataSource(new SqliteFunctionsDataSource(new DriverDataSource(hikari.getJdbcUrl(),
                    hikari.getDriverClassName(), hikari.getDataSourceProperties(), hikari.getUsername(),
                    hikari.getPassword())));
        }
        return bean;
    }
}
