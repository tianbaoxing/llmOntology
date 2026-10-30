package com.conca.ontology.model.api;

import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.model.entity.ModelInfoEntity;
import com.conca.ontology.model.service.ModelInfoService;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * AI 模型信息管理入口（/api/model-info，仅 ADMIN；model 为自然主键、任意文本 ≤160，
 * 可能含 "/"、空格等字符，故不用路径参数，统一 @RequestParam / 请求体传递）：
 *
 * GET    /api/model-info/page    → 分页 + 条件查询（model/organization 模糊、confidence 枚举、3 个布尔筛选）
 * GET    /api/model-info/detail  → 单条全字段（7 个大数列输出为字符串）
 * POST   /api/model-info         → 新增（model 查重）
 * PUT    /api/model-info         → 编辑（body.model 定位；除 lastModified 外全量覆盖）
 * DELETE /api/model-info         → 物理删除
 *
 * @author tianbaoxing
 * @date 2026-10-29
 */
@Tag(name = "Admin-ModelInfo", description = "AI 模型信息管理（model_info 表，源 Epoch AI 数据集，57 字段）："
        + "分页/详情/新增/编辑/删除；仅 ADMIN")
@RestController
@RequestMapping("/api/model-info")
public class ModelInfoController {

    private final ModelInfoService service;

    public ModelInfoController(ModelInfoService service) {
        this.service = service;
    }

    @Operation(summary = "分页列表", description = "page 从 1 起（默认 1），pageSize 默认 10、上限 100，越界自动收敛；"
            + "model/organization 模糊匹配（忽略大小写）；confidence 枚举精确匹配；"
            + "foundationModel/frontierModel/openWeights 传 true/false 参与过滤，不传不过滤；默认按 model 升序")
    @GetMapping("/page")
    public ApiResponse<ModelInfoService.PageView> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String organization,
            @RequestParam(required = false) String confidence,
            @RequestParam(required = false) Boolean foundationModel,
            @RequestParam(required = false) Boolean frontierModel,
            @RequestParam(required = false) Boolean openWeights,
            HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(service.page(page, pageSize, model, organization,
                confidence, foundationModel, frontierModel, openWeights));
    }

    @Operation(summary = "单条详情", description = "按 model 主键返回全字段（57 列）；不存在返回 404；"
            + "7 个 DECIMAL(45,10) 大数列（parameters、各 FLOP/规模列）以字符串返回，规避 JS 精度丢失")
    @GetMapping("/detail")
    public ApiResponse<ModelInfoEntity> detail(@RequestParam String model, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(service.detail(model));
    }

    @Operation(summary = "新增", description = "model 必填、≤160 且唯一（重复返回 409）；其余 56 字段可空；"
            + "VARCHAR 超列宽直接报错（不静默截断）；last_modified 由系统维护不填写")
    @PostMapping
    public ApiResponse<ModelInfoEntity> create(@RequestBody ModelInfoEntity request, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(service.create(request));
    }

    @Operation(summary = "编辑", description = "以 body.model 定位（主键不可变）；除 lastModified（源数据维护字段）外"
            + "全量覆盖：表单清空的字段写 NULL，未出现在请求体的字段同样写 NULL（前端始终提交全量表单）")
    @PutMapping
    public ApiResponse<ModelInfoEntity> update(@RequestBody ModelInfoEntity request, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(service.update(request));
    }

    @Operation(summary = "删除", description = "按 model 主键物理删除（独立域无外键，无级联影响），不可恢复")
    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam String model, HttpSession session) {
        requireAdmin(session);
        service.delete(model);
        return ApiResponse.ok(null);
    }

    // ---------------- 内部 ----------------

    /** 拦截器已保证登录；此处兜底防御性校验（与 AgentBuilderController 同款） */
    private static void requireAdmin(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        if (!u.isAdmin()) {
            throw new ApiException(403, "仅管理员可管理模型信息");
        }
    }
}
