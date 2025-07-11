package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static java.lang.Math.min;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLikes(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog==null) {
            return Result.fail("笔记不存在");
        }

        queryBlogUser(blog);

        isBlogLikes(blog);

        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);

        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLikes(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 判断是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);

    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 判断是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 未点赞,可以点赞
            boolean success = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id).update();

            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());

            }
        }else{
            //点赞了,取消点赞
            boolean success = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());

            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogByLikes(Long id) {
        // 查询top5的点赞用户(时间戳) zrange 0 4
        String key = BLOG_LIKED_KEY + id;

        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (range == null||range.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }
        // 解析出用户Id

        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", ids);
        List<UserDTO> list = userService.query()
                .in("id",ids)
                .last("order by FIELD(id,"+join+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(list);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if (!isSuccess) {
         return Result.fail("新增笔记失败");
        }
        // 查询粉丝
        List<Follow> fans = followService.query().eq("follow_user_id", blog.getUserId()).list();
        // 推送笔记id
        for (Follow follow : fans) {
            Long userId = follow.getUserId();
            // 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());

        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = FEED_KEY + userId;

        // ZSetOperations.TypedTuple<String> 是一个元组
        // 有value和score
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }


        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取blogid
            String idStr = typedTuple.getValue();
            Long id = Long.valueOf(idStr);
            ids.add(id);

            long time = typedTuple.getScore().longValue();
            if(time == minTime)
            {
                os++;
            }else {
                // 防止非最小数据重复时,os增加
                // 获取分数
                minTime = time;
                os =1;
            }
        }


        // 注意listByIds方法使用mysql的in语句,不能保证顺序,之前用了field解决
        String join = StringUtil.join(ids,",");
        List<Blog> blogs =query()
                .in("id",ids)
                .last("order by FIELD(id,"+join+")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);

            isBlogLikes(blog);
        }

        ScrollResult sr = new ScrollResult();
        sr.setList(blogs);
        sr.setOffset(os);
        sr.setMinTime(minTime);

        return Result.ok(sr);
    }
}
