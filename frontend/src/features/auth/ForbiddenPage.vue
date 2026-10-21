<template>
    <section class="forbidden-page page-content">
        <p class="eyebrow">ACCESS CONTROL</p>
        <h1>此账号没有访问权限</h1>
        <p>该页面需要其他角色。账号权限由服务端验证，普通用户注册不会自动获得主办方身份。</p>
        <RouterLink to="/" class="text-link">返回活动列表</RouterLink>
    </section>
</template>

<style scoped>
.forbidden-page {
    max-width: 680px;
    margin: 48px auto;
    padding: 36px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: #fff;
}
.forbidden-page p:not(.eyebrow) {
    color: var(--muted);
    line-height: 1.8;
}
.text-link {
    font-weight: 700;
}
</style>
