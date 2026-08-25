package com.basicframework.framework.ip.core.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ip.core.Area;
import java.util.List;
import org.junit.jupiter.api.Test;

class IPAndAreaUtilsTest {

    private static final String PUBLIC_DNS_IP = "114.114.114.114";

    @Test
    void areaCache_loadsRealResourceAndKeepsStableObjectIdentity() {
        Area china = AreaUtils.getArea(Area.ID_CHINA);

        assertThat(china.getName()).isEqualTo("中国");
        assertThat(china.getChildren()).isNotEmpty();
        assertThat(AreaUtils.getArea(Area.ID_CHINA)).isSameAs(china);
    }

    @Test
    void areaLookup_parsesAndFormatsRealAdministrativePath() {
        Area westLake = AreaUtils.parseArea("浙江省/杭州市/西湖区");

        assertThat(westLake).isNotNull();
        assertThat(westLake.getId()).isEqualTo(330106);
        assertThat(AreaUtils.format(westLake.getId())).isEqualTo("浙江省 杭州市 西湖区");
        assertThat(AreaUtils.getAreaNodePathList(List.of(westLake))).containsExactly("西湖区");
    }

    @Test
    void ipLookup_usesBundledIndexAndRejectsMalformedInput() {
        Integer areaId = IPUtils.getAreaId("  " + PUBLIC_DNS_IP + "  ");

        assertThat(areaId).isPositive();
        assertThat(IPUtils.getArea(PUBLIC_DNS_IP)).isSameAs(AreaUtils.getArea(areaId));
        assertThatThrownBy(() -> IPUtils.getAreaId("not-an-ip"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("invalid ip address");
    }
}
