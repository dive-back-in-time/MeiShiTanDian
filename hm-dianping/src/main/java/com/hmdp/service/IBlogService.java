package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 实现点赞功能
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    Result queryHotBlog(Integer current);

    /**
     * 查询点赞排行前五的用户
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
}
