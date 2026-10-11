package com.duoc.bancoxyz.authserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servidor de autorizacion OAuth 2.0.
 *
 * <p>Es el "guardian centralizado" de la Figura 1 de la guia: el unico
 * componente que conoce credenciales de usuario y el unico que emite tokens.
 * Los demas microservicios no validan contrasenas ni guardan sesiones; se
 * limitan a verificar la firma del token contra las claves publicas que este
 * servidor expone. Esa delegacion es lo que permite agregar un microservicio
 * nuevo sin volver a implementar autenticacion.</p>
 *
 * <p>Habilita dos flujos, porque el sistema tiene dos clases de cliente con
 * necesidades distintas:</p>
 * <ul>
 *   <li><b>authorization_code + PKCE</b> para personas. El usuario se
 *       autentica en este servidor -nunca entrega su contrasena a la
 *       aplicacion- y aprueba explicitamente que permisos concede. PKCE evita
 *       que un tercero que intercepte el codigo de autorizacion pueda
 *       canjearlo, porque no conoce el verificador que lo origino.</li>
 *   <li><b>client_credentials</b> para servicios. No hay usuario detras: un
 *       proceso automatico se identifica con sus propias credenciales para
 *       consumir otro servicio.</li>
 * </ul>
 */
@SpringBootApplication
public class AuthorizationServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthorizationServerApplication.class, args);
    }
}
