package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.AttachmentApplicationService;
import com.yumpoo.platform.filestorage.api.AttachmentModels.DownloadContent;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentControllerDownloadTest {
    @Test
    void emitsSafeDownloadHeadersAndStreamsWithoutDigestDisclosure() throws Exception {
        CurrentActorProvider actors=mock(CurrentActorProvider.class);
        AttachmentApplicationService service=mock(AttachmentApplicationService.class);
        CurrentActor actor=new CurrentActor(UUID.randomUUID(),UUID.randomUUID(),0,Set.of());
        UUID id=UUID.randomUUID();
        when(actors.requiredActive()).thenReturn(actor);
        when(service.download(actor,id)).thenReturn(new DownloadContent(
                new ByteArrayInputStream(new byte[]{1,2,3,4}),"验收 报告.txt","text/plain",4));
        AttachmentController controller=new AttachmentController(actors,service,null,null,null,null);

        ResponseEntity<StreamingResponseBody> response=controller.download(id);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(output.toByteArray()).containsExactly(1,2,3,4);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("UTF-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox");
        assertThat(response.getHeaders().getFirst("X-Content-SHA256")).isNull();
    }
}
