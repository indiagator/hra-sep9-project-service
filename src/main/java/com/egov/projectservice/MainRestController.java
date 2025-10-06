package com.egov.projectservice;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/")
public class MainRestController
{
    private static final Logger logger = LoggerFactory.getLogger(MainRestController.class);
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    TokenService tokenService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    private View error;

    @PostMapping("project/float") // Secured Endpoint need JWT Token in Header
    ResponseEntity<?> floatProject(@RequestBody Project project,
                                   HttpServletRequest request,
                                   HttpServletResponse httpServletResponse)
    {
        logger.info("Request received to float project");
        // TOKEN VALIDATION IS REQUIRED
        Optional<String> token =  Optional.ofNullable(tokenService.getAuthCookieValue(request));
        logger.info("Token extracted to float project {}: ",token);
        Optional<String> principal =  Optional.ofNullable(tokenService.validateToken(token.orElse(null)));
        logger.info("Principal extracted to float project {}: ",principal);

        if(principal.isPresent())
        {
            String floatProjectCookieValue = getAuthCookieValue(request, "float-project-1");
            // FRESH OR FOLLOW UP REQUEST DECIPHERING LOGIC CAN BE PLACED HERE
            if(floatProjectCookieValue == null)
            {
                logger.info("Fresh request for float project received");
                logger.info("Proceeding with project flotation for principal {}: ",principal.get());
                project.setStatus("FLOATED");
                Project savedProject =  projectRepository.save(project);
                logger.info("Saved project {}: ",savedProject.getId());
                // SET A COOKIE IN THE RESPONSE
                Cookie floatProjectStage1 = new Cookie("float-project-1", io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
                floatProjectStage1.setMaxAge(60);
                redisTemplate.opsForValue().set(floatProjectStage1.getValue(), "STAGE_1_COMPLETED_FOR_PROJECT_"+savedProject.getId()+" processing STAGE_2");

                //forward request to payment-service for payment [ service-fee ] creation
                WebClient paymentCreateWebClient = (WebClient) applicationContext.getBean("paymentCreateWebClient");
                paymentCreateWebClient.post().
                        uri("/"+savedProject.getId()+"/"+(savedProject.getBudget()/10)).
                        header("Authorization", token.get()).
                        retrieve().
                        bodyToMono(String.class). // ASYNC HANDLER LOGIC STARTS FROM THE NEXT LINE - WILL BE EXECUTED IN A SEPARATE THREAD
                        subscribe( response -> {

                    logger.info("Response from payment-service for project {}: is {} ",savedProject.getId(),response);
                    redisTemplate.opsForValue().set(floatProjectStage1.getValue()," STAGE_2_COMPLETED_FOR_PROJECT_"+savedProject.getId()+" with PAYMENT_ID_"+response+" TRANSACTION COMPLETE");
                    // CACHE UPDATION TAKES PLACE HERE

                }, error -> {
                            logger.error("Error in payment-service for project {}: ",savedProject.getId(),error);
                }); // ASYNC HANDLER LOGIC ENDS HERE

                httpServletResponse.addCookie(floatProjectStage1);
                return ResponseEntity.ok("PROJECT FLOATED WITH ID: "+savedProject.getId());
            }
            else
            {
                // FOLLOW UP REQUEST LOGIC CAN BE PLACED HERE
                logger.info("Follow up request for float project received");

                String cacheValue = redisTemplate.opsForValue().get(floatProjectCookieValue);

                assert cacheValue != null;
                if(cacheValue.contains(" processing STAGE_2"))
                {
                    return ResponseEntity.ok("REQUEST IS STILL UNDER PROCESS");
                }
                else
                {
                    return ResponseEntity.ok(cacheValue);
                }


            }
        }
        else
        {
            return ResponseEntity.status(401).body("BAD CREDENTIALS");
        }


    }

    String getAuthCookieValue(HttpServletRequest request, String cookiename)
    {
        List<Cookie> cookies = new ArrayList<>();

        if(!(request.getCookies() == null))
        {
            cookies = List.of(request.getCookies());
        }

        Optional<Cookie> authcookie =  cookies.stream().filter(cookie -> cookie.getName().equals(cookiename)).findFirst();

        return authcookie.map(Cookie::getValue).orElse(null);
    }

}
