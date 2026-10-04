package com.granatum.core.api.security

import com.granatum.core.api.config.JwtAuthFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless JWT-bearer security. Inventory endpoints require ADMIN or
 * ENCARGADO; EMPLEADO has no inventory access (it will only be granted
 * fichaje routes once the `timetracking` module lands). This is the only
 * place where public vs. protected routes are declared globally.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun filterChain(httpSecurity: HttpSecurity, jwtAuthFilter: JwtAuthFilter): SecurityFilterChain {
        return httpSecurity
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/", "/actuator/**", "/api/dev/**")
                    .permitAll()
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                    .permitAll()
                    .requestMatchers("/api/materiales/**", "/api/categorias/**")
                    .hasAnyRole("ADMIN", "ENCARGADO")
                    // Staff management is ADMIN only: ENCARGADO runs inventory
                    // and approves corrections, but does not manage people.
                    .requestMatchers("/api/empleados/**")
                    .hasRole("ADMIN")
                    // REPRESENTANTE is read-only over the register, so it may
                    // reach these paths with GET and nothing else. Resource
                    // ownership (an EMPLEADO only seeing their own) is checked
                    // in the service against the JWT subject, because a path
                    // matcher cannot express it.
                    .requestMatchers(HttpMethod.GET, "/api/fichajes/**", "/api/correcciones/**")
                    .hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")
                    .requestMatchers("/api/fichajes/**", "/api/correcciones/**")
                    .hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO")
                    .anyRequest()
                    .authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { configure ->
                configure.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .build()
    }
}
